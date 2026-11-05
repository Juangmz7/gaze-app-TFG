package presentation_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	followusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	followmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/mongo"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	userusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
	usermongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/mongo"
)

var (
	mongoOnce      sync.Once
	mongoClient    *mongodriver.Client
	mongoErr       error
	mongoTerminate func()
)

// TestMain shares one MongoDB container between the tests of this package, each on its
// own database. It starts only if a test asks for it.
func TestMain(m *testing.M) {
	code := m.Run()
	if mongoTerminate != nil {
		mongoTerminate()
	}
	os.Exit(code)
}

func testDatabase(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	mongoOnce.Do(func() {
		startCtx := context.Background()
		container, err := mongodb.Run(startCtx, "mongo:7")
		if err != nil {
			mongoErr = err
			return
		}
		mongoTerminate = func() { _ = container.Terminate(context.Background()) }
		connectionString, err := container.ConnectionString(startCtx)
		if err != nil {
			mongoErr = err
			return
		}
		mongoClient, mongoErr = mongodriver.Connect(options.Client().ApplyURI(connectionString))
	})
	if mongoErr != nil {
		t.Fatalf("start mongo: %v", mongoErr)
	}

	db := mongoClient.Database("feed_" + uuid.NewString()[:8])
	t.Cleanup(func() {
		if err := db.Drop(context.Background()); err != nil {
			t.Logf("Drop() error = %v", err)
		}
	})

	return db
}

// feedApp is the real stack behind the feed routes on a real MongoDB, with read models
// written by their real repositories.
type feedApp struct {
	db       *mongodriver.Database
	mux      *http.ServeMux
	viewerID uuid.UUID
	creator  uuid.UUID
	build    *usecase.BuildFeedUnitsUsecase
	posts    *postmongo.Repository
	users    *usermongo.Repository
}

// noPrefetch stands in for the prefetch, which has its own tests against a broker.
type noPrefetch struct{}

func (noPrefetch) Execute(context.Context, usecase.RequestFeedPrefetchInput) (usecase.RequestFeedPrefetchOutput, error) {
	return usecase.RequestFeedPrefetchOutput{Status: usecase.RequestFeedPrefetchPublished}, nil
}

func newFeedApp(t *testing.T, ctx context.Context) *feedApp {
	t.Helper()

	db := testDatabase(t, ctx)
	sessions := feedmongo.NewSessionRepository(db)
	units := feedmongo.NewFeedUnitRepository(db)
	hydration := feedmongo.NewHydrationRepository(db)
	if err := sessions.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := units.EnsureIndexes(ctx, time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	posts, users := postmongo.NewRepository(db), usermongo.NewRepository(db)
	for _, ensure := range []func(context.Context) error{posts.EnsureIndexes, users.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}

	viewerID, creator := uuid.New(), uuid.New()
	if err := users.Insert(ctx, userusecase.RegisterUserInput{UserID: creator, Username: "ana_rivera", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	logger := discardLogger()
	config := usecase.ClaimFeedConfig{DefaultPageSize: 15, MaxPageSize: 20, MaxReads: 3}
	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, viewerID.String()), presentation.Routes{
		Heartbeat:  presentation.NewHeartbeatHandler(usecase.NewRegisterHeartbeat(sessions, logger), logger),
		Disconnect: presentation.NewDisconnectHandler(usecase.NewRegisterUserDisconnected(sessions, noPrefetch{}, 5*time.Second, logger), logger),
		ClaimFeed:  presentation.NewClaimFeedHandler(usecase.NewClaimFeed(units, hydration, config, logger), logger),
	})

	return &feedApp{
		db:       db,
		mux:      mux,
		viewerID: viewerID,
		creator:  creator,
		build:    usecase.NewBuildFeedUnits(hydration, sessions, units, logger),
		posts:    posts,
		users:    users,
	}
}

// seedPosts projects count posts by the app's creator and builds their feed units.
func (a *feedApp) seedPosts(t *testing.T, ctx context.Context, count int) []string {
	t.Helper()

	postIDs := make([]uuid.UUID, 0, count)
	for index := range count {
		postID := uuid.New()
		postIDs = append(postIDs, postID)
		duration := int64(15000)
		thumbnail := "https://media.test/thumb.jpg"
		err := a.posts.Insert(ctx, postusecase.CreatePostInput{
			PostID:      postID,
			UserID:      a.creator,
			PostType:    "BASIC",
			Description: fmt.Sprintf("Post %d", index),
			Tags:        []string{"travel", "sunset"},
			Media: []postusecase.PostMedia{
				{ID: uuid.New(), URL: "https://media.test/b.mp4", ThumbnailURL: &thumbnail, MediaType: "VIDEO", Duration: &duration, TaggedUsers: []string{"ana"}, Order: 2},
				{ID: uuid.New(), URL: "https://media.test/a.jpg", MediaType: "IMAGE", Order: 1},
			},
			CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
			UpdatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
		})
		if err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
	}

	if _, err := a.build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: a.viewerID, PostIDs: postIDs}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	ids := make([]string, 0, count)
	for _, postID := range postIDs {
		ids = append(ids, postID.String())
	}
	return ids
}

type page struct {
	Items []struct {
		PostID   string `json:"postId"`
		Position int64  `json:"position"`
	} `json:"items"`
	LastSeenID *string `json:"lastSeenId"`
	HasMore    bool    `json:"hasMore"`
}

func (p page) postIDs() []string {
	postIDs := make([]string, 0, len(p.Items))
	for _, item := range p.Items {
		postIDs = append(postIDs, item.PostID)
	}
	return postIDs
}

func (a *feedApp) claim(t *testing.T, query string) page {
	t.Helper()

	recorder := serve(a.mux, http.MethodGet, "/api/feed"+query, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET /api/feed%s status = %d: %s", query, recorder.Code, recorder.Body)
	}
	var got page
	if err := json.Unmarshal(recorder.Body.Bytes(), &got); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return got
}

func TestFeedClaim_AdvancesAcrossConsecutiveCursorsAndNeverReplaysInANewSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 25)

	first := app.claim(t, "")
	if !slices.Equal(first.postIDs(), postIDs[:15]) || !first.HasMore || *first.LastSeenID != postIDs[14] {
		t.Fatalf("first page = %+v", first)
	}
	if first.Items[0].Position >= first.Items[1].Position {
		t.Fatalf("positions are not increasing: %+v", first.Items[:2])
	}

	// The next cursor returns exactly what remains, without padding.
	second := app.claim(t, "?lastSeenId="+*first.LastSeenID)
	if !slices.Equal(second.postIDs(), postIDs[15:]) || second.HasMore || *second.LastSeenID != postIDs[24] {
		t.Fatalf("second page = %+v", second)
	}

	// Repeating a cursor returns the same page, even though it was served already.
	repeated := app.claim(t, "?lastSeenId="+*first.LastSeenID)
	if !slices.Equal(repeated.postIDs(), second.postIDs()) {
		t.Fatalf("repeated page = %+v, want %+v", repeated.postIDs(), second.postIDs())
	}

	// Everything was served, so a reopened app gets an empty page, not a replay.
	reopened := app.claim(t, "")
	if len(reopened.Items) != 0 || reopened.LastSeenID != nil || reopened.HasMore {
		t.Fatalf("reopened page = %+v", reopened)
	}

	// New units arrive: the next session starts with them.
	fresh := app.seedPosts(t, ctx, 2)
	if got := app.claim(t, ""); !slices.Equal(got.postIDs(), fresh) {
		t.Fatalf("session after new units = %v, want %v", got.postIDs(), fresh)
	}
}

func TestFeedClaim_ASecondSessionContinuesAfterAPartlyServedFeed(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 10)

	if got := app.claim(t, "?limit=4"); !slices.Equal(got.postIDs(), postIDs[:4]) || !got.HasMore {
		t.Fatalf("first session = %+v", got)
	}
	if got := app.claim(t, ""); !slices.Equal(got.postIDs(), postIDs[4:]) || got.HasMore {
		t.Fatalf("second session = %+v", got)
	}
}

func TestFeedClaim_ACursorThatDoesNotResolveRestartsFromTheLowestUnservedPosition(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 6)
	app.claim(t, "?limit=2")

	if got := app.claim(t, "?lastSeenId="+uuid.NewString()); !slices.Equal(got.postIDs(), postIDs[2:]) {
		t.Fatalf("page = %v, want %v", got.postIDs(), postIDs[2:])
	}
}

func TestFeedClaim_RejectsALimitAboveTheMaximum(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)

	if recorder := serve(app.mux, http.MethodGet, "/api/feed?limit=21", "", nil); recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
	if recorder := serve(app.mux, http.MethodGet, "/api/feed?limit=0", "", nil); recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
}

func TestFeedClaim_ServesTheAgreedSchemaWithCurrentState(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 2)
	postID := uuid.MustParse(postIDs[0])

	// What happens after the unit was built must show up in the next claim.
	version, _, err := app.users.GetVersion(ctx, app.creator)
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	err = app.users.Update(ctx, userusecase.UpdateUserInput{UserID: app.creator, ExpectedVersion: version, Username: "ana_renamed", PictureURL: "https://media.test/ana.jpg", UpdatedAt: time.Now().UTC()})
	if err != nil {
		t.Fatalf("Update() error = %v", err)
	}
	if _, err := app.posts.AdjustLikeCount(ctx, postID, 3); err != nil {
		t.Fatalf("AdjustLikeCount() error = %v", err)
	}
	if _, err := app.db.Collection("post_likes").InsertOne(ctx, bson.D{{Key: "post_id", Value: postIDs[0]}, {Key: "user_id", Value: app.viewerID.String()}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if _, err := app.db.Collection("post_shares").InsertOne(ctx, bson.D{{Key: "post_id", Value: postIDs[1]}, {Key: "user_id", Value: app.viewerID.String()}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if err := followmongo.NewRepository(app.db).Insert(ctx, followusecase.CreateFollowInput{FollowerUserID: app.viewerID, FollowedUserID: app.creator, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	collabID := uuid.New()
	if err := collabmongo.NewRepository(app.db).Insert(ctx, collabusecase.RecordCollabOpenedInput{CollabID: collabID, PostID: postID, OwnerUserID: app.creator, Title: "Harbour", Status: "OPEN", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	recorder := serve(app.mux, http.MethodGet, "/api/feed", "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
	}

	var response map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	card := response["items"].([]any)[0].(map[string]any)

	if card["postId"] != postIDs[0] || card["position"] != float64(0) || card["description"] != "Post 0" {
		t.Fatalf("card = %v", card)
	}
	if card["createdAt"] != "2026-03-01T10:00:00Z" {
		t.Fatalf("createdAt = %v", card["createdAt"])
	}
	if card["postType"] != "COLLAB" || card["collabId"] != collabID.String() {
		t.Fatalf("postType = %v, collabId = %v", card["postType"], card["collabId"])
	}
	collab := card["collab"].(map[string]any)
	if collab["title"] != "Harbour" || collab["status"] != "OPEN" {
		t.Fatalf("collab = %v", collab)
	}
	creator := card["creator"].(map[string]any)
	if creator["userId"] != app.creator.String() || creator["username"] != "ana_renamed" || creator["pictureUrl"] != "https://media.test/ana.jpg" {
		t.Fatalf("creator = %v", creator)
	}
	if tags := card["postTags"].([]any); len(tags) != 2 || tags[1] != "sunset" {
		t.Fatalf("postTags = %v", tags)
	}
	media := card["media"].([]any)
	first, second := media[0].(map[string]any), media[1].(map[string]any)
	if first["mediaType"] != "IMAGE" || first["order"] != float64(1) || first["durationMs"] != nil || first["thumbnailUrl"] != nil {
		t.Fatalf("first media = %v", first)
	}
	if second["mediaType"] != "VIDEO" || second["order"] != float64(2) || second["durationMs"] != float64(15000) {
		t.Fatalf("second media = %v", second)
	}
	if card["coverThumbnailUrl"] != "https://media.test/a.jpg" || card["durationMs"] != float64(15000) {
		t.Fatalf("cover = %v, duration = %v", card["coverThumbnailUrl"], card["durationMs"])
	}
	if stats := card["stats"].(map[string]any); stats["likeCount"] != float64(3) || stats["commentCount"] != float64(0) || stats["shareCount"] != float64(0) {
		t.Fatalf("stats = %v", stats)
	}
	if viewer := card["viewer"].(map[string]any); viewer["liked"] != true || viewer["shared"] != false || viewer["followingCreator"] != true {
		t.Fatalf("viewer = %v", viewer)
	}
	second2 := response["items"].([]any)[1].(map[string]any)
	if second2["postType"] != "BASIC" || second2["collabId"] != nil || second2["collab"].(map[string]any)["title"] != nil {
		t.Fatalf("basic card = %v", second2)
	}
	if viewer := second2["viewer"].(map[string]any); viewer["liked"] != false || viewer["shared"] != true {
		t.Fatalf("viewer of the second card = %v", viewer)
	}

	// served_at is set on what was returned, and the snapshot's counters are untouched.
	var unit struct {
		ServedAt *time.Time `bson:"served_at"`
		Stats    struct {
			LikeCount int64 `bson:"like_count"`
		} `bson:"stats"`
	}
	err = app.db.Collection("feed_units").FindOne(ctx, bson.D{{Key: "post_id", Value: postIDs[0]}}).Decode(&unit)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if unit.ServedAt == nil || unit.Stats.LikeCount != 0 {
		t.Fatalf("stored unit = %+v, want served_at set and the snapshot untouched", unit)
	}
}

func TestFeedClaim_ServesTheCollabPostCommandServiceHolds(t *testing.T) {
	cases := map[string]struct {
		// stale makes posts.collab_id name the older collab, as for a post created with it.
		stale bool
	}{
		"two collabs found by post id alone":  {stale: false},
		"a stale posts.collab_id on the post": {stale: true},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			defer cancel()
			app := newFeedApp(t, ctx)
			postIDs := app.seedPosts(t, ctx, 1)
			postID := uuid.MustParse(postIDs[0])

			opened := time.Now().UTC().Truncate(time.Millisecond)
			older, newer := uuid.New(), uuid.New()
			collabs := collabmongo.NewRepository(app.db)
			// Inserted newest first, so natural order would serve the older one.
			if err := collabs.Insert(ctx, collabusecase.RecordCollabOpenedInput{CollabID: newer, PostID: postID, OwnerUserID: app.creator, Title: "New", Status: "OPEN", CreatedAt: opened}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			if err := collabs.Insert(ctx, collabusecase.RecordCollabOpenedInput{CollabID: older, PostID: postID, OwnerUserID: app.creator, Title: "Old", Status: "CLOSED", CreatedAt: opened.Add(-time.Hour)}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			if tc.stale {
				if _, err := app.db.Collection("posts").UpdateOne(ctx, bson.D{{Key: "post_id", Value: postIDs[0]}}, bson.D{{Key: "$set", Value: bson.D{{Key: "collab_id", Value: older.String()}}}}); err != nil {
					t.Fatalf("UpdateOne() error = %v", err)
				}
			}

			recorder := serve(app.mux, http.MethodGet, "/api/feed", "", nil)
			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
			}
			var response map[string]any
			if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			card := response["items"].([]any)[0].(map[string]any)
			collab := card["collab"].(map[string]any)

			if card["collabId"] != newer.String() || collab["title"] != "New" || collab["status"] != "OPEN" || card["postType"] != "COLLAB" {
				t.Fatalf("card collabId = %v, collab = %v, postType = %v, want the newest collab %s", card["collabId"], collab, card["postType"], newer)
			}
		})
	}
}

func TestFeedClaim_DropsDeletedPostsAndBlocksInEitherDirectionAndStillFillsThePage(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 6)

	if err := app.posts.Delete(ctx, uuid.MustParse(postIDs[0])); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	got := app.claim(t, "?limit=3")
	if !slices.Equal(got.postIDs(), postIDs[1:4]) || !got.HasMore {
		t.Fatalf("page = %+v, want it filled past the deleted post", got)
	}

	blocks := blockmongo.NewRepository(app.db)
	if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.creator, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if got := app.claim(t, "?lastSeenId="+postIDs[3]); len(got.Items) != 0 {
		t.Fatalf("page = %+v, want nothing from a creator blocking the viewer", got)
	}

	if err := blocks.Delete(ctx, app.creator, app.viewerID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}
	if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.viewerID, BlockedUserID: app.creator, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if got := app.claim(t, "?lastSeenId="+postIDs[3]); len(got.Items) != 0 {
		t.Fatalf("page = %+v, want nothing from a creator the viewer blocks", got)
	}
}

func TestFeedClaim_ACancelledRequestAbortsTheMongoCalls(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	app.seedPosts(t, ctx, 3)

	requestCtx, cancelRequest := context.WithCancel(ctx)
	cancelRequest()
	request := httptest.NewRequest(http.MethodGet, "/api/feed", nil).WithContext(requestCtx)
	recorder := httptest.NewRecorder()
	app.mux.ServeHTTP(recorder, request)

	if recorder.Code != 499 {
		t.Fatalf("status = %d, want 499: %s", recorder.Code, recorder.Body)
	}
	served, err := app.db.Collection("feed_units").CountDocuments(ctx, bson.D{{Key: "served_at", Value: bson.D{{Key: "$ne", Value: nil}}}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if served != 0 {
		t.Fatalf("served units = %d, want 0: the claim must abort", served)
	}
}

func TestPresenceEndpoints_WriteOneDocumentPerUser(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	sessions := app.db.Collection("user_feed_sessions")
	filter := bson.D{{Key: "user_id", Value: app.viewerID.String()}}

	var session struct {
		LastHeartbeatDate *time.Time `bson:"last_heartbeat_date"`
		DisconnectedAt    *time.Time `bson:"disconnected_at"`
		PrefetchDue       bool       `bson:"prefetch_due"`
	}

	if recorder := serve(app.mux, http.MethodPost, "/api/feed/heartbeat", "", nil); recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d", recorder.Code)
	}
	if err := sessions.FindOne(ctx, filter).Decode(&session); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	firstHeartbeat := *session.LastHeartbeatDate
	if !session.PrefetchDue || session.DisconnectedAt != nil {
		t.Fatalf("session after a heartbeat = %+v", session)
	}

	time.Sleep(5 * time.Millisecond)
	if recorder := serve(app.mux, http.MethodPost, "/api/feed/heartbeat", "", nil); recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d", recorder.Code)
	}
	if err := sessions.FindOne(ctx, filter).Decode(&session); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if !session.LastHeartbeatDate.After(firstHeartbeat) {
		t.Fatalf("last_heartbeat_date = %v, want it after %v", session.LastHeartbeatDate, firstHeartbeat)
	}

	// The client closes right after sending the disconnect: the write still lands.
	requestCtx, cancelRequest := context.WithCancel(ctx)
	cancelRequest()
	request := httptest.NewRequest(http.MethodPost, "/api/feed/disconnect", nil).WithContext(requestCtx)
	recorder := httptest.NewRecorder()
	app.mux.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want 202: %s", recorder.Code, recorder.Body)
	}
	if err := sessions.FindOne(ctx, filter).Decode(&session); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if session.DisconnectedAt == nil || !session.LastHeartbeatDate.Equal(*session.DisconnectedAt) {
		t.Fatalf("session after a disconnect = %+v", session)
	}

	// A new heartbeat clears the disconnect.
	serve(app.mux, http.MethodPost, "/api/feed/heartbeat", "", nil)
	var after struct {
		DisconnectedAt *time.Time `bson:"disconnected_at"`
	}
	if err := sessions.FindOne(ctx, filter).Decode(&after); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if after.DisconnectedAt != nil {
		t.Fatalf("disconnected_at = %v, want it cleared", after.DisconnectedAt)
	}

	count, err := sessions.CountDocuments(ctx, filter)
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("presence documents = %d, want 1", count)
	}
}
