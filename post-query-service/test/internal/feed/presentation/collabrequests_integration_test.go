package presentation_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// openCollab projects a collab owned by owner on postID, with collab_members indexed.
func (a *feedApp) openCollab(t *testing.T, ctx context.Context, owner, postID uuid.UUID) uuid.UUID {
	t.Helper()

	collabs, members := collabmongo.NewRepository(a.db), collabmongo.NewMemberRepository(a.db)
	for _, ensure := range []func(context.Context) error{collabs.EnsureIndexes, members.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}
	collabID := uuid.New()
	err := collabs.Insert(ctx, collabusecase.RecordCollabOpenedInput{
		CollabID: collabID, PostID: postID, OwnerUserID: owner, Title: "Harbour series", Status: "OPEN", CreatedAt: commentsEpoch,
	})
	if err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	return collabID
}

// applyMember projects a membership event through the real usecase and repository, as
// the RabbitMQ handlers do.
func (a *feedApp) applyMember(t *testing.T, ctx context.Context, collabID, userID uuid.UUID, status string, requestedAt, occurredAt time.Time) {
	t.Helper()

	_, err := collabusecase.NewApplyCollabMember(collabmongo.NewMemberRepository(a.db)).Execute(ctx, collabusecase.ApplyCollabMemberInput{
		CollabID: collabID, UserID: userID, Status: status, Role: collabusecase.CollabMemberRoleMember, RequestedAt: requestedAt, OccurredAt: occurredAt,
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
}

// collabRequestState reads collab.pendingRequestCount and viewer.collabRequestStatus off
// a feed item or a post view.
func collabRequestState(t *testing.T, item map[string]any) (any, any) {
	t.Helper()

	collab, ok := item["collab"].(map[string]any)
	if !ok {
		t.Fatalf("item has no collab: %v", item)
	}
	viewer, ok := item["viewer"].(map[string]any)
	if !ok {
		t.Fatalf("item has no viewer: %v", item)
	}
	count, hasCount := collab["pendingRequestCount"]
	status, hasStatus := viewer["collabRequestStatus"]
	if !hasCount || !hasStatus {
		t.Fatalf("item = %v, want collab.pendingRequestCount and viewer.collabRequestStatus present, null or not", item)
	}
	return count, status
}

func getJSON(t *testing.T, mux *http.ServeMux, target string) map[string]any {
	t.Helper()

	recorder := serve(mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return body
}

func firstItem(t *testing.T, body map[string]any) map[string]any {
	t.Helper()

	items, ok := body["items"].([]any)
	if !ok || len(items) == 0 {
		t.Fatalf("body = %v, want at least one item", body)
	}
	return items[0].(map[string]any)
}

// Acceptance: the caller who asked to join sees PENDING on the feed item, the post view
// and the following feed, and REJECTED after the decline; with 3 pending requests the
// owner sees 3 and everyone else null.
func TestCollabRequests_FeedPostViewAndFollowingFeedCarryTheCallersStatusAndOnlyTheOwnerSeesTheCount(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	owner := world.creator
	postIDs := world.seedPosts(t, ctx, 2)
	first, second := uuid.MustParse(postIDs[0]), uuid.MustParse(postIDs[1])
	collabID := world.openCollab(t, ctx, owner, first)
	// The second post names the collab: both posts of the viewer's feed resolve it.
	if _, err := world.db.Collection("posts").UpdateOne(ctx, bson.D{{Key: "post_id", Value: postIDs[1]}}, bson.D{{Key: "$set", Value: bson.D{{Key: "collab_id", Value: collabID.String()}}}}); err != nil {
		t.Fatalf("UpdateOne() error = %v", err)
	}
	world.follow(t, ctx, world.viewerID, owner, commentsEpoch)
	other := world.registerUser(t, ctx, "bruno")
	world.follow(t, ctx, other, owner, commentsEpoch)

	// Three pending requests (the viewer's among them) and one accepted member.
	world.applyMember(t, ctx, collabID, world.viewerID, "PENDING", commentsEpoch, commentsEpoch)
	for index := range 2 {
		world.applyMember(t, ctx, collabID, uuid.New(), "PENDING", commentsEpoch.Add(time.Duration(index+1)*time.Minute), commentsEpoch)
	}
	world.applyMember(t, ctx, collabID, uuid.New(), "ACCEPTED", commentsEpoch, commentsEpoch.Add(time.Hour))

	viewerMux, ownerMux, otherMux := world.mux, world.muxFor(t, owner), world.muxFor(t, other)

	// The viewer: PENDING everywhere, and no count.
	feedItem := firstItem(t, getJSON(t, viewerMux, "/api/feed?limit=1"))
	views := map[string]map[string]any{
		"feed item":      feedItem,
		"post view":      getJSON(t, viewerMux, "/api/content/posts/"+first.String()),
		"following feed": firstItem(t, getJSON(t, viewerMux, "/api/feed/following")),
	}
	for name, item := range views {
		if count, status := collabRequestState(t, item); count != nil || status != "PENDING" {
			t.Fatalf("viewer's %s: pendingRequestCount = %v, collabRequestStatus = %v, want null and PENDING", name, count, status)
		}
	}

	// The owner sees 3 (the accepted member is not pending), with no status of their own.
	if count, status := collabRequestState(t, getJSON(t, ownerMux, "/api/content/posts/"+first.String())); count != float64(3) || status != nil {
		t.Fatalf("owner's post view: pendingRequestCount = %v, collabRequestStatus = %v, want 3 and null", count, status)
	}
	if _, err := world.build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: owner, PostIDs: []uuid.UUID{first}}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if count, _ := collabRequestState(t, firstItem(t, getJSON(t, ownerMux, "/api/feed"))); count != float64(3) {
		t.Fatalf("owner's feed item: pendingRequestCount = %v, want 3", count)
	}

	// Anyone else: null and null.
	for name, item := range map[string]map[string]any{
		"post view":      getJSON(t, otherMux, "/api/content/posts/"+first.String()),
		"following feed": firstItem(t, getJSON(t, otherMux, "/api/feed/following")),
	} {
		if count, status := collabRequestState(t, item); count != nil || status != nil {
			t.Fatalf("another user's %s: pendingRequestCount = %v, collabRequestStatus = %v, want both null", name, count, status)
		}
	}

	// The owner declines the viewer: the next feed item, the post view and the following
	// feed show REJECTED, and the owner's count drops to 2.
	world.applyMember(t, ctx, collabID, world.viewerID, "REJECTED", commentsEpoch, commentsEpoch.Add(2*time.Hour))
	next := getJSON(t, viewerMux, "/api/feed?limit=1&lastSeenId="+feedItem["postId"].(string))
	nextItem := firstItem(t, next)
	if nextItem["postId"] != second.String() && nextItem["postId"] != first.String() {
		t.Fatalf("next feed item = %v, want the viewer's other unit", nextItem["postId"])
	}
	for name, item := range map[string]map[string]any{
		"feed item":      nextItem,
		"post view":      getJSON(t, viewerMux, "/api/content/posts/"+second.String()),
		"following feed": firstItem(t, getJSON(t, viewerMux, "/api/feed/following")),
	} {
		if _, status := collabRequestState(t, item); status != "REJECTED" {
			t.Fatalf("viewer's %s after the decline: collabRequestStatus = %v, want REJECTED", name, status)
		}
	}
	if count, _ := collabRequestState(t, getJSON(t, ownerMux, "/api/content/posts/"+first.String())); count != float64(2) {
		t.Fatalf("owner's count after the decline = %v, want 2", count)
	}

	// A BASIC post carries neither field.
	basic := world.insertPostBy(t, ctx, owner, commentsEpoch)
	if count, status := collabRequestState(t, getJSON(t, viewerMux, "/api/content/posts/"+basic.String())); count != nil || status != nil {
		t.Fatalf("BASIC post view: pendingRequestCount = %v, collabRequestStatus = %v, want both null", count, status)
	}
}

// The owner's page reads collab_members twice whatever the page size: one find for the
// caller's statuses and one aggregation for the counts, which reads index keys only.
func TestCollabRequests_TheFeedReadsCollabMembersOncePerLookupForThePage(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postIDs := app.seedPosts(t, ctx, 6)
	for _, postID := range postIDs {
		collabID := app.openCollab(t, ctx, app.viewerID, uuid.MustParse(postID))
		for index := range 3 {
			app.applyMember(t, ctx, collabID, uuid.New(), "PENDING", commentsEpoch.Add(time.Duration(index)*time.Minute), commentsEpoch)
		}
	}

	restartProfiler(t, ctx, app.db)
	page := getJSON(t, app.mux, "/api/feed?limit=6")
	if items := page["items"].([]any); len(items) != 6 {
		t.Fatalf("items = %d, want 6", len(items))
	}
	for _, item := range page["items"].([]any) {
		if count, _ := collabRequestState(t, item.(map[string]any)); count != float64(3) {
			t.Fatalf("own collab count = %v, want 3", count)
		}
	}

	operations := profiledOperations(t, ctx, app.db, "collab_members")
	var finds, aggregates []profiledOperation
	for _, operation := range operations {
		switch {
		case operation.Op == "query":
			finds = append(finds, operation)
		case operation.Op == "command" && operation.isAggregate():
			aggregates = append(aggregates, operation)
		}
	}
	if len(finds) != 1 || len(aggregates) != 1 {
		t.Fatalf("collab_members operations = %d finds and %d aggregations, want one of each for the page", len(finds), len(aggregates))
	}
	if aggregates[0].DocsExamined != 0 || !strings.Contains(aggregates[0].PlanSummary, "IXSCAN") || !strings.Contains(aggregates[0].PlanSummary, "requested_at") {
		t.Fatalf("count plan = %s with %d documents examined, want a covered scan of the requests index", aggregates[0].PlanSummary, aggregates[0].DocsExamined)
	}
}

// profiledOperation is what the profiler recorded about one operation, find or command.
type profiledOperation struct {
	Op           string   `bson:"op"`
	Command      bson.Raw `bson:"command"`
	PlanSummary  string   `bson:"planSummary"`
	DocsExamined int64    `bson:"docsExamined"`
}

func (o profiledOperation) isAggregate() bool {
	_, err := o.Command.LookupErr("aggregate")
	return err == nil
}

func profiledOperations(t *testing.T, ctx context.Context, db *mongodriver.Database, collection string) []profiledOperation {
	t.Helper()

	cursor, err := db.Collection("system.profile").Find(ctx, bson.D{{Key: "ns", Value: db.Name() + "." + collection}}, options.Find().SetSort(bson.D{{Key: "ts", Value: 1}}))
	if err != nil {
		t.Fatalf("read the profiler: %v", err)
	}
	var operations []profiledOperation
	if err := cursor.All(ctx, &operations); err != nil {
		t.Fatalf("read the profiler: %v", err)
	}
	return operations
}
