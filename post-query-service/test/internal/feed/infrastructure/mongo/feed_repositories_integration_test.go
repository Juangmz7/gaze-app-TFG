package mongo_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/event"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
	sharemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/mongo"
)

var (
	mongoOnce      sync.Once
	mongoClient    *mongodriver.Client
	mongoErr       error
	mongoURI       string
	mongoTerminate func()
)

// TestMain shares one MongoDB container between this package's tests, each on its own
// database.
func TestMain(m *testing.M) {
	code := m.Run()
	if mongoTerminate != nil {
		mongoTerminate()
	}
	os.Exit(code)
}

func testDatabase(t *testing.T) *mongodriver.Database {
	t.Helper()

	mongoOnce.Do(func() {
		ctx := context.Background()
		container, err := mongodb.Run(ctx, "mongo:7")
		if err != nil {
			mongoErr = err
			return
		}
		mongoTerminate = func() { _ = container.Terminate(context.Background()) }
		connectionString, err := container.ConnectionString(ctx)
		if err != nil {
			mongoErr = err
			return
		}
		mongoURI = connectionString
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

func testContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	t.Cleanup(cancel)
	return ctx
}

func sessionRepository(t *testing.T, ctx context.Context, db *mongodriver.Database) *feedmongo.SessionRepository {
	t.Helper()
	repository := feedmongo.NewSessionRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	return repository
}

func unitRepository(t *testing.T, ctx context.Context, db *mongodriver.Database) *feedmongo.FeedUnitRepository {
	t.Helper()
	repository := feedmongo.NewFeedUnitRepository(db)
	if err := repository.EnsureIndexes(ctx, time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	return repository
}

type indexInfo struct {
	Name               string `bson:"name"`
	Key                bson.D `bson:"key"`
	Unique             bool   `bson:"unique"`
	ExpireAfterSeconds *int32 `bson:"expireAfterSeconds"`
}

func listIndexes(t *testing.T, ctx context.Context, collection *mongodriver.Collection) map[string]indexInfo {
	t.Helper()

	cursor, err := collection.Indexes().List(ctx)
	if err != nil {
		t.Fatalf("Indexes().List() error = %v", err)
	}
	var indexes []indexInfo
	if err := cursor.All(ctx, &indexes); err != nil {
		t.Fatalf("cursor.All() error = %v", err)
	}
	byName := make(map[string]indexInfo, len(indexes))
	for _, index := range indexes {
		byName[index.Name] = index
	}
	return byName
}

func TestSessionRepository_EnsureIndexes_CreatesTheAgreedIndexes(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	sessionRepository(t, ctx, db)

	indexes := listIndexes(t, ctx, db.Collection("user_feed_sessions"))
	if index, found := indexes["user_id_1"]; !found || !index.Unique {
		t.Fatalf("user_id index = %+v (found %v), want unique", index, found)
	}
	if _, found := indexes["prefetch_due_1_last_heartbeat_date_1"]; !found {
		t.Fatalf("indexes = %v, want (prefetch_due, last_heartbeat_date)", indexes)
	}
}

func TestSessionRepository_FirstWriteCreatesADocumentWithEveryField(t *testing.T) {
	writes := map[string]func(*feedmongo.SessionRepository, context.Context, uuid.UUID) error{
		"heartbeat": func(r *feedmongo.SessionRepository, ctx context.Context, userID uuid.UUID) error {
			return r.RecordHeartbeat(ctx, userID, time.Now().UTC())
		},
		"disconnect": func(r *feedmongo.SessionRepository, ctx context.Context, userID uuid.UUID) error {
			return r.RecordDisconnect(ctx, userID, time.Now().UTC())
		},
		"reservation": func(r *feedmongo.SessionRepository, ctx context.Context, userID uuid.UUID) error {
			_, err := r.ReservePositions(ctx, userID, 2)
			return err
		},
	}

	for name, write := range writes {
		t.Run(name, func(t *testing.T) {
			ctx := testContext(t)
			db := testDatabase(t)
			repository := sessionRepository(t, ctx, db)
			userID := uuid.New()

			if err := write(repository, ctx, userID); err != nil {
				t.Fatalf("write error = %v", err)
			}

			var document bson.M
			if err := db.Collection("user_feed_sessions").FindOne(ctx, bson.D{{Key: "user_id", Value: userID.String()}}).Decode(&document); err != nil {
				t.Fatalf("FindOne() error = %v", err)
			}
			for _, field := range []string{
				"user_id", "last_heartbeat_date", "disconnected_at", "last_prefetch_at", "prefetch_due",
				"next_position", "prefetch_lease_owner", "prefetch_lease_token", "prefetch_lease_expires_at",
			} {
				if _, found := document[field]; !found {
					t.Fatalf("document = %v misses %s", document, field)
				}
			}
		})
	}
}

// Each worker's first write races to create the document: the unique index must leave
// exactly one, and no worker may see an error.
func TestSessionRepository_ConcurrentHeartbeatsLeaveOneDocumentPerUser(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := sessionRepository(t, ctx, db)
	userID := uuid.New()

	const workers = 32
	errs := make(chan error, workers)
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			errs <- repository.RecordHeartbeat(ctx, userID, time.Now().UTC())
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatalf("RecordHeartbeat() error = %v", err)
		}
	}

	count, err := db.Collection("user_feed_sessions").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("documents = %d, want 1", count)
	}
}

func TestSessionRepository_ReservePositions_RejectsANonPositiveCount(t *testing.T) {
	ctx := testContext(t)
	repository := sessionRepository(t, ctx, testDatabase(t))

	if _, err := repository.ReservePositions(ctx, uuid.New(), 0); err == nil {
		t.Fatal("ReservePositions() error = nil, want an error")
	}
}

func TestSessionRepository_DeleteByUser_IsIdempotent(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := sessionRepository(t, ctx, db)
	userID, other := uuid.New(), uuid.New()
	for _, id := range []uuid.UUID{userID, other} {
		if err := repository.RecordHeartbeat(ctx, id, time.Now().UTC()); err != nil {
			t.Fatalf("RecordHeartbeat() error = %v", err)
		}
	}

	for range 2 {
		if err := repository.DeleteByUser(ctx, userID); err != nil {
			t.Fatalf("DeleteByUser() error = %v", err)
		}
	}

	count, err := db.Collection("user_feed_sessions").CountDocuments(ctx, bson.D{})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("documents = %d, want only the other user's", count)
	}
}

func TestFeedUnitRepository_EnsureIndexes_CreatesTheAgreedIndexesAndFollowsARetentionChange(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := unitRepository(t, ctx, db)
	collection := db.Collection("feed_units")

	indexes := listIndexes(t, ctx, collection)
	if index, found := indexes["user_id_1_post_id_1"]; !found || !index.Unique {
		t.Fatalf("(user_id, post_id) = %+v (found %v), want unique", index, found)
	}
	if index, found := indexes["user_id_1_position_1"]; !found || !index.Unique {
		t.Fatalf("(user_id, position) = %+v (found %v), want unique", index, found)
	}
	for _, name := range []string{"post_id_1", "creator.user_id_1", "user_id_1_served_at_1_position_1"} {
		if _, found := indexes[name]; !found {
			t.Fatalf("indexes = %v, want %s", indexes, name)
		}
	}
	if index := indexes["served_at_1"]; index.ExpireAfterSeconds == nil || *index.ExpireAfterSeconds != 3600 {
		t.Fatalf("served_at index = %+v, want a TTL of one hour", index)
	}

	// A new retention must not stop the service from starting.
	if err := repository.EnsureIndexes(ctx, 2*time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() with a new retention error = %v", err)
	}
	indexes = listIndexes(t, ctx, collection)
	if index := indexes["served_at_1"]; index.ExpireAfterSeconds == nil || *index.ExpireAfterSeconds != 7200 {
		t.Fatalf("served_at index = %+v, want the new retention", index)
	}

	if err := repository.EnsureIndexes(ctx, 0); err == nil {
		t.Fatal("EnsureIndexes() with no retention error = nil, want an error")
	}
}

func feedUnit(userID, postID string, position int64) usecase.FeedUnit {
	return usecase.FeedUnit{
		UserID:        userID,
		PostID:        postID,
		Position:      position,
		Creator:       usecase.FeedCreator{UserID: uuid.NewString(), Username: "ana"},
		PostType:      "BASIC",
		Tags:          []string{"travel"},
		PostCreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
		IngestedAt:    time.Now().UTC(),
	}
}

func TestFeedUnitRepository_InsertUnits_TreatsDuplicatesAsAppliedAndStoresTheRestOfTheBatch(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := unitRepository(t, ctx, db)
	userID := uuid.NewString()
	existing := uuid.NewString()

	inserted, err := repository.InsertUnits(ctx, []usecase.FeedUnit{feedUnit(userID, existing, 0)})
	if err != nil || inserted != 1 {
		t.Fatalf("InsertUnits() = %d, %v", inserted, err)
	}

	// The duplicate comes first: an ordered insert would stop there.
	newPost := uuid.NewString()
	inserted, err = repository.InsertUnits(ctx, []usecase.FeedUnit{feedUnit(userID, existing, 1), feedUnit(userID, newPost, 2)})
	if err != nil {
		t.Fatalf("InsertUnits() error = %v", err)
	}
	if inserted != 1 {
		t.Fatalf("inserted = %d, want 1", inserted)
	}

	count, err := db.Collection("feed_units").CountDocuments(ctx, bson.D{{Key: "user_id", Value: userID}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 2 {
		t.Fatalf("documents = %d, want 2", count)
	}

	if inserted, err := repository.InsertUnits(ctx, nil); err != nil || inserted != 0 {
		t.Fatalf("InsertUnits(nil) = %d, %v", inserted, err)
	}
}

func TestFeedUnitRepository_InsertUnits_ReportsAFailureThatIsNotADuplicate(t *testing.T) {
	ctx := testContext(t)
	repository := unitRepository(t, ctx, testDatabase(t))

	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := repository.InsertUnits(cancelled, []usecase.FeedUnit{feedUnit(uuid.NewString(), uuid.NewString(), 0)}); !errors.Is(err, context.Canceled) {
		t.Fatalf("InsertUnits() error = %v, want context.Canceled", err)
	}
}

func TestFeedUnitRepository_ReadsInPositionOrderAndFiltersWhatWasServed(t *testing.T) {
	ctx := testContext(t)
	repository := unitRepository(t, ctx, testDatabase(t))
	userID := uuid.NewString()

	postIDs := []string{uuid.NewString(), uuid.NewString(), uuid.NewString(), uuid.NewString()}
	// Stored out of order and with a gap in positions, both of which are valid.
	units := []usecase.FeedUnit{
		feedUnit(userID, postIDs[2], 7),
		feedUnit(userID, postIDs[0], 1),
		feedUnit(userID, postIDs[3], 9),
		feedUnit(userID, postIDs[1], 2),
		feedUnit(uuid.NewString(), postIDs[0], 1),
	}
	if _, err := repository.InsertUnits(ctx, units); err != nil {
		t.Fatalf("InsertUnits() error = %v", err)
	}

	refs := func(after *int64, unservedOnly bool, limit int) []string {
		t.Helper()
		found, err := repository.FindAfterPosition(ctx, userID, after, unservedOnly, limit)
		if err != nil {
			t.Fatalf("FindAfterPosition() error = %v", err)
		}
		ids := make([]string, 0, len(found))
		for _, ref := range found {
			if ref.PostCreatedAt.IsZero() {
				t.Fatalf("ref = %+v, want its creation date", ref)
			}
			ids = append(ids, ref.PostID)
		}
		return ids
	}

	if got := refs(nil, true, 10); !slices.Equal(got, postIDs) {
		t.Fatalf("unserved = %v, want %v", got, postIDs)
	}
	after := int64(2)
	if got := refs(&after, false, 1); !slices.Equal(got, postIDs[2:3]) {
		t.Fatalf("after position 2, limit 1 = %v", got)
	}

	if err := repository.MarkServed(ctx, userID, postIDs[:2], time.Now().UTC()); err != nil {
		t.Fatalf("MarkServed() error = %v", err)
	}
	if got := refs(nil, true, 10); !slices.Equal(got, postIDs[2:]) {
		t.Fatalf("unserved after serving two = %v", got)
	}
	if got := refs(nil, false, 10); !slices.Equal(got, postIDs) {
		t.Fatalf("all = %v: a cursor read must not depend on served_at", got)
	}

	position, found, err := repository.FindPosition(ctx, userID, postIDs[2])
	if err != nil || !found || position != 7 {
		t.Fatalf("FindPosition() = %d, %v, %v", position, found, err)
	}
	if _, found, err := repository.FindPosition(ctx, uuid.NewString(), postIDs[2]); err != nil || found {
		t.Fatalf("FindPosition() for another user: found %v, %v", found, err)
	}

	for _, test := range []struct {
		after        *int64
		unservedOnly bool
		want         bool
	}{
		{nil, true, true},
		{&after, true, true},
		{pointer(int64(9)), false, false},
		{pointer(int64(9)), true, false},
	} {
		got, err := repository.HasAfterPosition(ctx, userID, test.after, test.unservedOnly)
		if err != nil || got != test.want {
			t.Fatalf("HasAfterPosition(%v, %v) = %v, %v, want %v", test.after, test.unservedOnly, got, err, test.want)
		}
	}
}

func pointer[T any](value T) *T {
	return &value
}

func TestFeedUnitRepository_MarkServed_KeepsTheFirstServedDate(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := unitRepository(t, ctx, db)
	userID, postID := uuid.NewString(), uuid.NewString()
	if _, err := repository.InsertUnits(ctx, []usecase.FeedUnit{feedUnit(userID, postID, 0)}); err != nil {
		t.Fatalf("InsertUnits() error = %v", err)
	}

	first := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	for _, servedAt := range []time.Time{first, first.Add(time.Hour)} {
		if err := repository.MarkServed(ctx, userID, []string{postID}, servedAt); err != nil {
			t.Fatalf("MarkServed() error = %v", err)
		}
	}

	var document struct {
		ServedAt time.Time `bson:"served_at"`
	}
	if err := db.Collection("feed_units").FindOne(ctx, bson.D{{Key: "post_id", Value: postID}}).Decode(&document); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if !document.ServedAt.Equal(first) {
		t.Fatalf("served_at = %v, want %v", document.ServedAt, first)
	}
}

func TestFeedUnitRepository_Clean_up(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := unitRepository(t, ctx, db)
	collection := db.Collection("feed_units")

	viewer, deletedUser := uuid.New(), uuid.New()
	deletedPost := uuid.New()

	kept := feedUnit(viewer.String(), uuid.NewString(), 0)
	ofDeletedPost := feedUnit(viewer.String(), deletedPost.String(), 1)
	ofDeletedPostElsewhere := feedUnit(uuid.NewString(), deletedPost.String(), 0)
	ownFeed := feedUnit(deletedUser.String(), uuid.NewString(), 0)
	byDeletedUser := feedUnit(viewer.String(), uuid.NewString(), 2)
	byDeletedUser.Creator.UserID = deletedUser.String()

	if _, err := repository.InsertUnits(ctx, []usecase.FeedUnit{kept, ofDeletedPost, ofDeletedPostElsewhere, ownFeed, byDeletedUser}); err != nil {
		t.Fatalf("InsertUnits() error = %v", err)
	}

	for range 2 {
		if err := repository.DeleteByPost(ctx, deletedPost); err != nil {
			t.Fatalf("DeleteByPost() error = %v", err)
		}
	}
	if count, err := collection.CountDocuments(ctx, bson.D{{Key: "post_id", Value: deletedPost.String()}}); err != nil || count != 0 {
		t.Fatalf("units of the deleted post = %d, %v", count, err)
	}

	for range 2 {
		if err := repository.DeleteByUserOrCreator(ctx, deletedUser); err != nil {
			t.Fatalf("DeleteByUserOrCreator() error = %v", err)
		}
	}
	var remaining []struct {
		PostID string `bson:"post_id"`
	}
	cursor, err := collection.Find(ctx, bson.D{})
	if err != nil {
		t.Fatalf("Find() error = %v", err)
	}
	if err := cursor.All(ctx, &remaining); err != nil {
		t.Fatalf("cursor.All() error = %v", err)
	}
	if len(remaining) != 1 || remaining[0].PostID != kept.PostID {
		t.Fatalf("remaining = %+v, want only the unrelated unit", remaining)
	}
}

// Overlapping builds of the same posts: reservations never overlap, the unique indexes
// hold, and a repeated batch creates no duplicates.
func TestBuildFeedUnits_ConcurrentReservationsNeverShareAPositionAndARepeatedBatchAddsNothing(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	sessions := sessionRepository(t, ctx, db)
	units := unitRepository(t, ctx, db)
	hydration := feedmongo.NewHydrationRepository(db)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	build := usecase.NewBuildFeedUnits(hydration, sessions, units, logger)

	creator := uuid.NewString()
	if _, err := db.Collection("users").InsertOne(ctx, bson.D{{Key: "user_id", Value: creator}, {Key: "username", Value: "ana"}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	const postCount = 40
	postIDs := make([]uuid.UUID, 0, postCount)
	for range postCount {
		postID := uuid.New()
		postIDs = append(postIDs, postID)
		if _, err := db.Collection("posts").InsertOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: creator}, {Key: "post_type", Value: "BASIC"}}); err != nil {
			t.Fatalf("InsertOne() error = %v", err)
		}
	}

	viewer := uuid.New()

	// Raw reservations racing each other.
	const workers = 16
	const perWorker = 5
	starts := make(chan int64, workers)
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			first, err := sessions.ReservePositions(ctx, viewer, perWorker)
			if err != nil {
				t.Errorf("ReservePositions() error = %v", err)
				return
			}
			starts <- first
		})
	}
	wg.Wait()
	close(starts)
	seen := map[int64]bool{}
	for first := range starts {
		for offset := range int64(perWorker) {
			if seen[first+offset] {
				t.Fatalf("position %d reserved twice", first+offset)
			}
			seen[first+offset] = true
		}
	}
	if len(seen) != workers*perWorker {
		t.Fatalf("reserved positions = %d, want %d", len(seen), workers*perWorker)
	}

	// Builders racing over the same batch.
	for range 8 {
		wg.Go(func() {
			if _, err := build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: viewer, PostIDs: postIDs}); err != nil {
				t.Errorf("Execute() error = %v", err)
			}
		})
	}
	wg.Wait()

	assertUnique := func() {
		t.Helper()
		cursor, err := db.Collection("feed_units").Find(ctx, bson.D{{Key: "user_id", Value: viewer.String()}})
		if err != nil {
			t.Fatalf("Find() error = %v", err)
		}
		var documents []struct {
			PostID   string `bson:"post_id"`
			Position int64  `bson:"position"`
		}
		if err := cursor.All(ctx, &documents); err != nil {
			t.Fatalf("cursor.All() error = %v", err)
		}
		if len(documents) != postCount {
			t.Fatalf("units = %d, want %d", len(documents), postCount)
		}
		posts, positions := map[string]bool{}, map[int64]bool{}
		for _, document := range documents {
			if posts[document.PostID] || positions[document.Position] {
				t.Fatalf("duplicate unit: %+v", document)
			}
			posts[document.PostID], positions[document.Position] = true, true
		}
	}
	assertUnique()

	// A repeated batch inserts nothing; it only burns positions, and gaps are allowed.
	output, err := build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: viewer, PostIDs: postIDs})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if output.Inserted != 0 {
		t.Fatalf("inserted = %d, want 0", output.Inserted)
	}
	assertUnique()
}

func TestHydrationRepository_ReadsTheCurrentReadModelsWithProjections(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	hydration := feedmongo.NewHydrationRepository(db)
	viewer, creator, stranger := uuid.NewString(), uuid.NewString(), uuid.NewString()
	liked, shared, untouched := uuid.NewString(), uuid.NewString(), uuid.NewString()

	seed := func(collection string, documents ...bson.D) {
		t.Helper()
		if _, err := db.Collection(collection).InsertMany(ctx, documents); err != nil {
			t.Fatalf("InsertMany(%s) error = %v", collection, err)
		}
	}
	seed("post_likes", bson.D{{Key: "post_id", Value: liked}, {Key: "user_id", Value: viewer}}, bson.D{{Key: "post_id", Value: shared}, {Key: "user_id", Value: stranger}})
	seed("post_shares", bson.D{{Key: "post_id", Value: shared}, {Key: "user_id", Value: viewer}}, bson.D{{Key: "post_id", Value: liked}, {Key: "user_id", Value: stranger}})
	seed("user_follows", bson.D{{Key: "follower_user_id", Value: viewer}, {Key: "followed_user_id", Value: creator}}, bson.D{{Key: "follower_user_id", Value: stranger}, {Key: "followed_user_id", Value: stranger}})
	seed("users", bson.D{{Key: "user_id", Value: creator}, {Key: "username", Value: "ana"}, {Key: "email", Value: "secret@example.test"}, {Key: "picture_url", Value: ""}})
	seed("post_collabs", bson.D{{Key: "collab_id", Value: "by-id"}, {Key: "post_id", Value: "another-post"}, {Key: "title", Value: "A"}, {Key: "status", Value: "OPEN"}},
		bson.D{{Key: "collab_id", Value: "by-post"}, {Key: "post_id", Value: liked}, {Key: "title", Value: "B"}, {Key: "status", Value: "CLOSED"}},
		bson.D{{Key: "collab_id", Value: "unrelated"}, {Key: "post_id", Value: "unrelated"}})

	postIDs := []string{liked, shared, untouched}

	if got, err := hydration.FindLikedPostIDs(ctx, viewer, postIDs); err != nil || !slices.Equal(got, []string{liked}) {
		t.Fatalf("FindLikedPostIDs() = %v, %v", got, err)
	}
	if got, err := hydration.FindSharedPostIDs(ctx, viewer, postIDs); err != nil || !slices.Equal(got, []string{shared}) {
		t.Fatalf("FindSharedPostIDs() = %v, %v", got, err)
	}
	if got, err := hydration.FindFollowedUserIDs(ctx, viewer, []string{creator, stranger}); err != nil || !slices.Equal(got, []string{creator}) {
		t.Fatalf("FindFollowedUserIDs() = %v, %v", got, err)
	}

	creators, err := hydration.FindCreators(ctx, []string{creator, stranger})
	if err != nil || len(creators) != 1 || creators[0].Username != "ana" || creators[0].PictureURL != nil {
		t.Fatalf("FindCreators() = %+v, %v", creators, err)
	}

	collabs, err := hydration.FindCollabs(ctx, []string{"by-id"}, []string{liked})
	if err != nil || len(collabs) != 2 {
		t.Fatalf("FindCollabs() = %+v, %v, want the collab named by id and the one attached to the post", collabs, err)
	}
	if collabs, err := hydration.FindCollabs(ctx, nil, []string{liked}); err != nil || len(collabs) != 1 {
		t.Fatalf("FindCollabs(nil, posts) = %+v, %v", collabs, err)
	}

	for name, call := range map[string]func() (int, error){
		"posts":   func() (int, error) { found, err := hydration.FindPosts(ctx, nil); return len(found), err },
		"users":   func() (int, error) { found, err := hydration.FindCreators(ctx, nil); return len(found), err },
		"collabs": func() (int, error) { found, err := hydration.FindCollabs(ctx, nil, nil); return len(found), err },
		"likes": func() (int, error) {
			found, err := hydration.FindLikedPostIDs(ctx, viewer, nil)
			return len(found), err
		},
		"follows": func() (int, error) {
			found, err := hydration.FindFollowedUserIDs(ctx, viewer, nil)
			return len(found), err
		},
		"blocks": func() (int, error) {
			found, err := hydration.FindBlockRelatedUserIDs(ctx, viewer, nil)
			return len(found), err
		},
	} {
		if found, err := call(); err != nil || found != 0 {
			t.Fatalf("%s with no ids = %d, %v", name, found, err)
		}
	}
}

func TestHydrationRepository_FindBlockRelatedUserIDs_FindsBothDirections(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	hydration := feedmongo.NewHydrationRepository(db)
	viewer, blockedByViewer, blockingViewer, unrelated := uuid.NewString(), uuid.NewString(), uuid.NewString(), uuid.NewString()

	documents := []bson.D{
		{{Key: "blocker_user_id", Value: viewer}, {Key: "blocked_user_id", Value: blockedByViewer}},
		{{Key: "blocker_user_id", Value: blockingViewer}, {Key: "blocked_user_id", Value: viewer}},
		{{Key: "blocker_user_id", Value: unrelated}, {Key: "blocked_user_id", Value: blockedByViewer}},
	}
	if _, err := db.Collection("user_blocks").InsertMany(ctx, documents); err != nil {
		t.Fatalf("InsertMany() error = %v", err)
	}

	got, err := hydration.FindBlockRelatedUserIDs(ctx, viewer, []string{blockedByViewer, blockingViewer, unrelated})
	if err != nil {
		t.Fatalf("FindBlockRelatedUserIDs() error = %v", err)
	}
	slices.Sort(got)
	want := []string{blockedByViewer, blockingViewer}
	slices.Sort(want)
	if !slices.Equal(got, want) {
		t.Fatalf("related = %v, want %v", got, want)
	}
}

func TestHydrationRepository_ACancelledContextReachesMongo(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	hydration := feedmongo.NewHydrationRepository(db)
	units := unitRepository(t, ctx, db)

	cancelled, cancel := context.WithCancel(ctx)
	cancel()

	calls := map[string]func() error{
		"FindPosts":               func() error { _, err := hydration.FindPosts(cancelled, []string{"a"}); return err },
		"FindCreators":            func() error { _, err := hydration.FindCreators(cancelled, []string{"a"}); return err },
		"FindCollabs":             func() error { _, err := hydration.FindCollabs(cancelled, []string{"a"}, []string{"a"}); return err },
		"FindLikedPostIDs":        func() error { _, err := hydration.FindLikedPostIDs(cancelled, "v", []string{"a"}); return err },
		"FindSharedPostIDs":       func() error { _, err := hydration.FindSharedPostIDs(cancelled, "v", []string{"a"}); return err },
		"FindFollowedUserIDs":     func() error { _, err := hydration.FindFollowedUserIDs(cancelled, "v", []string{"a"}); return err },
		"FindBlockRelatedUserIDs": func() error { _, err := hydration.FindBlockRelatedUserIDs(cancelled, "v", []string{"a"}); return err },
		"FindPosition":            func() error { _, _, err := units.FindPosition(cancelled, "v", "a"); return err },
		"FindAfterPosition":       func() error { _, err := units.FindAfterPosition(cancelled, "v", nil, true, 1); return err },
		"HasAfterPosition":        func() error { _, err := units.HasAfterPosition(cancelled, "v", nil, true); return err },
		"MarkServed":              func() error { return units.MarkServed(cancelled, "v", []string{"a"}, time.Now()) },
	}
	for name, call := range calls {
		t.Run(name, func(t *testing.T) {
			if err := call(); !errors.Is(err, context.Canceled) {
				t.Fatalf("error = %v, want context.Canceled", err)
			}
		})
	}
}

// The indexes the claim's queries rely on, owned by each read model's own repository.
func TestReadModelRepositories_EnsureTheIndexesTheClaimNeeds(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)

	for name, ensure := range map[string]func(context.Context) error{
		"post_likes":   likemongo.NewRepository(db).EnsureIndexes,
		"post_shares":  sharemongo.NewRepository(db).EnsureIndexes,
		"user_blocks":  blockmongo.NewRepository(db).EnsureIndexes,
		"post_collabs": collabmongo.NewRepository(db).EnsureIndexes,
	} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes(%s) error = %v", name, err)
		}
	}

	for collection, want := range map[string][]string{
		"post_likes":   {"post_id_1_user_id_1", "user_id_1_post_id_1"},
		"post_shares":  {"post_id_1_user_id_1", "user_id_1_post_id_1"},
		"user_blocks":  {"blocker_user_id_1_blocked_user_id_1", "blocked_user_id_1_blocker_user_id_1"},
		"post_collabs": {"collab_id_1", "post_id_1"},
	} {
		indexes := listIndexes(t, ctx, db.Collection(collection))
		for _, name := range want {
			if _, found := indexes[name]; !found {
				t.Fatalf("%s indexes = %v, want %s", collection, indexes, name)
			}
		}
	}
}

// Both claim reads must come from an index, in position order, without a blocking sort.
func TestFeedUnitRepository_ClaimReadsUseAnIndexWithoutSorting(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	repository := unitRepository(t, ctx, db)
	userID := uuid.NewString()

	units := make([]usecase.FeedUnit, 0, 50)
	for position := range int64(50) {
		units = append(units, feedUnit(userID, uuid.NewString(), position))
	}
	if _, err := repository.InsertUnits(ctx, units); err != nil {
		t.Fatalf("InsertUnits() error = %v", err)
	}

	for name, filter := range map[string]bson.D{
		"lowest unserved": {{Key: "user_id", Value: userID}, {Key: "served_at", Value: nil}},
		"after a cursor":  {{Key: "user_id", Value: userID}, {Key: "position", Value: bson.D{{Key: "$gt", Value: int64(10)}}}},
	} {
		t.Run(name, func(t *testing.T) {
			command := bson.D{{Key: "explain", Value: bson.D{
				{Key: "find", Value: "feed_units"},
				{Key: "filter", Value: filter},
				{Key: "sort", Value: bson.D{{Key: "position", Value: 1}}},
				{Key: "limit", Value: 15},
			}}}
			var explain struct {
				QueryPlanner struct {
					WinningPlan bson.Raw `bson:"winningPlan"`
				} `bson:"queryPlanner"`
			}
			if err := db.RunCommand(ctx, command).Decode(&explain); err != nil {
				t.Fatalf("explain error = %v", err)
			}
			plan := explain.QueryPlanner.WinningPlan.String()
			if !strings.Contains(plan, "IXSCAN") || strings.Contains(plan, `"SORT"`) || strings.Contains(plan, "COLLSCAN") {
				t.Fatalf("winning plan = %s, want an index scan with no sort", plan)
			}
		})
	}
}

func TestHydrationRepository_FindCollabsReadsWhenEachCollabWasOpened(t *testing.T) {
	ctx := testContext(t)
	db := testDatabase(t)
	opened := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	if _, err := db.Collection("post_collabs").InsertOne(ctx, bson.D{{Key: "collab_id", Value: "c1"}, {Key: "post_id", Value: "p1"}, {Key: "created_at", Value: opened}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}

	collabs, err := feedmongo.NewHydrationRepository(db).FindCollabs(ctx, nil, []string{"p1"})
	if err != nil || len(collabs) != 1 {
		t.Fatalf("FindCollabs() = %+v, %v", collabs, err)
	}
	if !collabs[0].CreatedAt.Equal(opened) {
		t.Fatalf("CreatedAt = %v, want %v", collabs[0].CreatedAt, opened)
	}
}

// The write's limit is the caller's deadline, so a configured
// FEED_DISCONNECT_WRITE_TIMEOUT_MS above operationTimeout (5s) takes effect. The driver
// sends what remains of the context's deadline to the server as maxTimeMS.
func TestSessionRepository_RecordDisconnect_IsBoundedByTheCallersDeadline(t *testing.T) {
	testDatabase(t)

	var maxTimeMS []int64
	monitor := &event.CommandMonitor{Started: func(_ context.Context, started *event.CommandStartedEvent) {
		if started.CommandName == "update" {
			value, err := started.Command.LookupErr("maxTimeMS")
			if err == nil {
				maxTimeMS = append(maxTimeMS, value.AsInt64())
			}
		}
	}}
	client, err := mongodriver.Connect(options.Client().ApplyURI(mongoURI).SetMonitor(monitor))
	if err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	t.Cleanup(func() { _ = client.Disconnect(context.Background()) })
	db := client.Database("feed_" + uuid.NewString()[:8])
	t.Cleanup(func() { _ = db.Drop(context.Background()) })

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	if err := feedmongo.NewSessionRepository(db).RecordDisconnect(ctx, uuid.New(), time.Now().UTC()); err != nil {
		t.Fatalf("RecordDisconnect() error = %v", err)
	}

	if len(maxTimeMS) != 1 || maxTimeMS[0] <= (5*time.Second).Milliseconds() {
		t.Fatalf("maxTimeMS = %v, want one write bounded by the caller's 60s, not 5s", maxTimeMS)
	}
}
