package bootstrap_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/lestrrat-go/jwx/v2/jwt"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// feedOnBroker adds the real feed stack to the real consumers, so a test can go from a
// literal Java event on a broker to a card in a claim.
type feedOnBroker struct {
	*javaEventsApp
	mux    *http.ServeMux
	viewer uuid.UUID
	build  *usecase.BuildFeedUnitsUsecase
}

func startFeedOnBroker(t *testing.T, ctx context.Context) *feedOnBroker {
	t.Helper()

	app := startJavaEventsApp(t, ctx)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	sessions := feedmongo.NewSessionRepository(app.db)
	units := feedmongo.NewFeedUnitRepository(app.db)
	hydration := feedmongo.NewHydrationRepository(app.db)
	if err := sessions.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := units.EnsureIndexes(ctx, time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	viewer := uuid.New()
	token, err := jwt.NewBuilder().Subject(viewer.String()).Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	authenticate := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), auth.TokenClaimsKey, token)))
		})
	}

	// A claim that leaves less than a page asks for more, the same as in main.
	lowWatermark := usecase.NewRequestLowWatermarkPrefetch(app.backlog, app.prefetch, usecase.RequestLowWatermarkPrefetchConfig{PageSize: 15, Concurrency: 8, Timeout: 10 * time.Second}, logger)
	t.Cleanup(lowWatermark.Wait)

	mux := http.NewServeMux()
	config := usecase.ClaimFeedConfig{DefaultPageSize: 15, MaxPageSize: 15, MaxReads: 3}
	claim := usecase.NewClaimFeed(units, hydration, config, logger).WithLowWatermark(lowWatermark)
	presentation.RegisterRoutes(mux, authenticate, presentation.Routes{
		Heartbeat:  presentation.NewHeartbeatHandler(usecase.NewRegisterHeartbeat(sessions, logger), logger),
		Disconnect: presentation.NewDisconnectHandler(usecase.NewRegisterUserDisconnected(sessions, app.prefetch, 5*time.Second, logger), logger),
		ClaimFeed:  presentation.NewClaimFeedHandler(claim, logger),
	})

	return &feedOnBroker{
		javaEventsApp: app,
		mux:           mux,
		viewer:        viewer,
		build:         usecase.NewBuildFeedUnits(hydration, sessions, units, logger),
	}
}

func (f *feedOnBroker) registerUser(t *testing.T, ctx context.Context, userID, username string) {
	t.Helper()

	f.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserRegistered, fmt.Sprintf(
		`{%s,"userId":"%s","username":"%s","email":"%s@example.test","bio":{"description":"Photographer","socialMedia":{}}}`,
		envelope(), userID, username, username))
	var user bson.M
	f.waitForDocument(t, ctx, "users", bson.D{{Key: "user_id", Value: userID}}, &user)
}

func (f *feedOnBroker) createPost(t *testing.T, ctx context.Context, postID, userID string) {
	t.Helper()

	f.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCreated, postCreatedJSON(postID, userID))
	var post bson.M
	f.waitForDocument(t, ctx, "posts", bson.D{{Key: "post_id", Value: postID}}, &post)
}

func (f *feedOnBroker) buildUnits(t *testing.T, ctx context.Context, viewer uuid.UUID, postIDs ...string) {
	t.Helper()

	ids := make([]uuid.UUID, 0, len(postIDs))
	for _, postID := range postIDs {
		ids = append(ids, uuid.MustParse(postID))
	}
	output, err := f.build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: viewer, PostIDs: ids})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if output.Inserted != len(postIDs) {
		t.Fatalf("output = %+v, want %d units", output, len(postIDs))
	}
}

// claim returns the cards of a page of the viewer's feed.
func (f *feedOnBroker) claim(t *testing.T, query string) []map[string]any {
	t.Helper()

	request := httptest.NewRequest(http.MethodGet, "/api/feed"+query, nil)
	recorder := httptest.NewRecorder()
	f.mux.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
	}
	var body struct {
		Items []map[string]any `json:"items"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return body.Items
}

func (f *feedOnBroker) countUnits(t *testing.T, ctx context.Context, filter bson.D) int64 {
	t.Helper()

	count, err := f.db.Collection("feed_units").CountDocuments(ctx, filter)
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	return count
}

func waitUntilCount(t *testing.T, ctx context.Context, collection *mongo.Collection, filter bson.D, want int64) {
	t.Helper()

	deadline := time.Now().Add(30 * time.Second)
	for {
		count, err := collection.CountDocuments(ctx, filter)
		if err != nil {
			t.Fatalf("CountDocuments() error = %v", err)
		}
		if count == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s matching %v = %d, want %d", collection.Name(), filter, count, want)
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			t.Fatal("context cancelled while waiting")
		}
	}
}

func TestFeedOnBroker_AClaimServesCountersFromConsumedEventsWithoutAnyFanOut(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	app := startFeedOnBroker(t, ctx)
	ex := topology.ExchangePostEvents

	creator, anchorPost, postID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	app.registerUser(t, ctx, creator, "ana_rivera")
	app.createPost(t, ctx, anchorPost, creator)
	app.createPost(t, ctx, postID, creator)
	app.buildUnits(t, ctx, app.viewer, anchorPost, postID)

	// Everything from the Java event on: postTags became tags and back, media survived.
	cards := app.claim(t, "?lastSeenId="+anchorPost)
	if len(cards) != 1 {
		t.Fatalf("cards = %v, want one", cards)
	}
	card := cards[0]
	if tags := card["postTags"].([]any); !slices.Equal(tags, []any{"travel", "sunset"}) {
		t.Fatalf("postTags = %v", tags)
	}
	if card["durationMs"] != float64(15000) || card["coverThumbnailUrl"] != "https://media.example.test/sunset.jpg" {
		t.Fatalf("durationMs = %v, cover = %v", card["durationMs"], card["coverThumbnailUrl"])
	}
	media := card["media"].([]any)
	if len(media) != 2 || media[0].(map[string]any)["mediaType"] != "IMAGE" || media[1].(map[string]any)["durationMs"] != float64(15000) {
		t.Fatalf("media = %v", media)
	}
	if creatorCard := card["creator"].(map[string]any); creatorCard["username"] != "ana_rivera" || creatorCard["pictureUrl"] != nil {
		t.Fatalf("creator = %v", creatorCard)
	}

	stored := bson.D{{Key: "post_id", Value: postID}, {Key: "tags", Value: "travel"}, {Key: "stats.like_count", Value: 0}}
	if count := app.countUnits(t, ctx, stored); count != 1 {
		t.Fatalf("stored units matching %v = %d, want 1", stored, count)
	}

	viewer := app.viewer.String()
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, likeCreatedJSON(postID, viewer))
	app.publish(t, ctx, ex, topology.RKPostLikeCreated, likeCreatedJSON(postID, uuid.NewString()))
	app.publish(t, ctx, ex, topology.RKPostCommentCreated, fmt.Sprintf(`{%s,"commentId":"%s","postId":"%s","userId":"%s","content":"Lovely light","replyTo":null,"createdAt":"%s","updatedAt":"%s"}`,
		envelope(), uuid.NewString(), postID, viewer, testInstant, testInstant))
	app.publish(t, ctx, ex, topology.RKPostShareCreated, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s","createdAt":"%s"}`, envelope(), postID, viewer, testInstant))
	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserFollowCreated, fmt.Sprintf(`{%s,"followerUserId":"%s","followedUserId":"%s"}`, envelope(), viewer, creator))
	app.waitForStats(t, ctx, postID, postStats{LikeCount: 2, CommentCount: 1, ShareCount: 1})
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)
	waitUntilCount(t, ctx, app.db.Collection("user_follows"), bson.D{}, 1)

	card = app.claim(t, "?lastSeenId="+anchorPost)[0]
	stats := card["stats"].(map[string]any)
	if stats["likeCount"] != float64(2) || stats["commentCount"] != float64(1) || stats["shareCount"] != float64(1) {
		t.Fatalf("stats = %v", stats)
	}
	viewerState := card["viewer"].(map[string]any)
	if viewerState["liked"] != true || viewerState["shared"] != true || viewerState["followingCreator"] != true {
		t.Fatalf("viewer = %v", viewerState)
	}

	// The counters reached the claim without touching the stored snapshot.
	if count := app.countUnits(t, ctx, stored); count != 1 {
		t.Fatalf("the stats snapshot changed: stored units matching %v = %d, want 1", stored, count)
	}
}

// A collab opened on an existing post only publishes CollabOpenedEvent: the claim must
// still present that post as COLLAB with its title and status.
func TestFeedOnBroker_ACollabOpenedOnAnExistingPostReachesTheClaim(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	app := startFeedOnBroker(t, ctx)

	creator, postID, collabID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	app.registerUser(t, ctx, creator, "ana_rivera")
	app.createPost(t, ctx, postID, creator)
	app.buildUnits(t, ctx, app.viewer, postID)

	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabOpenedExistingPost, fmt.Sprintf(
		`{%s,"collabId":"%s","title":"Harbour series","createdBy":"%s","collabStatus":"OPEN","collabCreatedAt":"%s","creatorMemberStatus":"ACCEPTED","creatorRole":"ADMIN","creatorMemberCreatedAt":"%s","postId":"%s","userId":"%s","postCollabId":"%s","postType":"COLLAB","description":"Sunset","postTags":[],"media":[],"postCreatedAt":"%s","postUpdatedAt":"%s","withPostCreated":false}`,
		envelope(), collabID, creator, testInstant, testInstant, postID, creator, collabID, testInstant, testInstant))
	var collab bson.M
	app.waitForDocument(t, ctx, "post_collabs", bson.D{{Key: "collab_id", Value: collabID}}, &collab)
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)

	cards := app.claim(t, "")
	if len(cards) != 1 {
		t.Fatalf("cards = %v, want one", cards)
	}
	card := cards[0]
	if card["postType"] != "COLLAB" || card["collabId"] != collabID {
		t.Fatalf("postType = %v, collabId = %v", card["postType"], card["collabId"])
	}
	if got := card["collab"].(map[string]any); got["title"] != "Harbour series" || got["status"] != "OPEN" {
		t.Fatalf("collab = %v", got)
	}
}

func TestFeedOnBroker_DeletingAUserOrAPostCleansUpTheFeed(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	app := startFeedOnBroker(t, ctx)

	creator, deletedCreator, deletedViewer := uuid.NewString(), uuid.NewString(), uuid.New()
	keptPost, deletedPost, postOfDeletedCreator := uuid.NewString(), uuid.NewString(), uuid.NewString()
	app.registerUser(t, ctx, creator, "ana_rivera")
	app.registerUser(t, ctx, deletedCreator, "leo_marin")
	app.createPost(t, ctx, keptPost, creator)
	app.createPost(t, ctx, deletedPost, creator)
	app.createPost(t, ctx, postOfDeletedCreator, deletedCreator)

	app.buildUnits(t, ctx, app.viewer, keptPost, deletedPost, postOfDeletedCreator)
	app.buildUnits(t, ctx, deletedViewer, keptPost)

	// A deleted post disappears from every feed.
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostDeleted, fmt.Sprintf(`{%s,"postId":"%s","userId":"%s"}`, envelope(), deletedPost, creator))
	waitUntilCount(t, ctx, app.db.Collection("feed_units"), bson.D{{Key: "post_id", Value: deletedPost}}, 0)

	// A deleted creator's posts disappear from other feeds.
	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserDeleted, fmt.Sprintf(`{%s,"userId":"%s"}`, envelope(), deletedCreator))
	waitUntilCount(t, ctx, app.db.Collection("feed_units"), bson.D{{Key: "creator.user_id", Value: deletedCreator}}, 0)

	// A deleted viewer loses their feed and their presence document.
	sessions := app.db.Collection("user_feed_sessions")
	waitUntilCount(t, ctx, sessions, bson.D{{Key: "user_id", Value: deletedViewer.String()}}, 1)
	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserDeleted, fmt.Sprintf(`{%s,"userId":"%s"}`, envelope(), deletedViewer))
	waitUntilCount(t, ctx, sessions, bson.D{{Key: "user_id", Value: deletedViewer.String()}}, 0)
	waitUntilCount(t, ctx, app.db.Collection("feed_units"), bson.D{{Key: "user_id", Value: deletedViewer.String()}}, 0)

	// What is left is untouched and is what a claim serves.
	cards := app.claim(t, "")
	if len(cards) != 1 || cards[0]["postId"] != keptPost {
		t.Fatalf("cards = %v, want only the kept post", cards)
	}
}
