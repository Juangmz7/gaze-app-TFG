package mongo_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
	viewmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/mongo"
)

const testRetention = 90 * 24 * time.Hour

func testDatabase(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	container, err := mongodb.Run(ctx, "mongo:7")
	if err != nil {
		t.Fatalf("mongodb.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})
	connectionString, err := container.ConnectionString(ctx)
	if err != nil {
		t.Fatalf("ConnectionString() error = %v", err)
	}
	client, err := mongodriver.Connect(options.Client().ApplyURI(connectionString))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := client.Disconnect(context.Background()); disconnectErr != nil {
			t.Logf("client.Disconnect() error = %v", disconnectErr)
		}
	})

	return client.Database("post_query_view_history_test")
}

type historyRow struct {
	UserID       string    `bson:"user_id"`
	PostID       string    `bson:"post_id"`
	LastViewedAt time.Time `bson:"last_viewed_at"`
}

func rowsOf(t *testing.T, ctx context.Context, db *mongodriver.Database, userID uuid.UUID) []historyRow {
	t.Helper()

	cursor, err := db.Collection(viewmongo.CollectionName).Find(ctx, bson.D{{Key: "user_id", Value: userID.String()}})
	if err != nil {
		t.Fatalf("Find() error = %v", err)
	}
	var rows []historyRow
	if err := cursor.All(ctx, &rows); err != nil {
		t.Fatalf("All() error = %v", err)
	}
	return rows
}

func TestHistoryRepository_Record_KeepsOneRowPerUserAndPostWithTheLatestView(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := testDatabase(t, ctx)
	repository := viewmongo.NewHistoryRepository(db)
	if err := repository.EnsureIndexes(ctx, testRetention); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	viewer, other, postID := uuid.New(), uuid.New(), uuid.New()
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	// Ten views of one post; the newest arrives before two older ones delivered late.
	for _, minute := range []int{0, 1, 2, 3, 4, 5, 6, 9, 7, 8} {
		input := usecase.RecordPostViewInput{UserID: viewer, PostID: postID, ViewedAt: base.Add(time.Duration(minute) * time.Minute)}
		if err := repository.Record(ctx, input); err != nil {
			t.Fatalf("Record() error = %v", err)
		}
	}
	if err := repository.Record(ctx, usecase.RecordPostViewInput{UserID: other, PostID: postID, ViewedAt: base}); err != nil {
		t.Fatalf("Record() error = %v", err)
	}

	rows := rowsOf(t, ctx, db, viewer)
	if len(rows) != 1 {
		t.Fatalf("viewer rows = %d, want 1 for ten views of one post", len(rows))
	}
	if !rows[0].LastViewedAt.Equal(base.Add(9*time.Minute)) || rows[0].PostID != postID.String() {
		t.Fatalf("row = %+v, want the post at the latest view %v", rows[0], base.Add(9*time.Minute))
	}
	if others := rowsOf(t, ctx, db, other); len(others) != 1 || !others[0].LastViewedAt.Equal(base) {
		t.Fatalf("other user's rows = %+v, want their own single row", others)
	}
}

func TestHistoryRepository_EnsureIndexes_CreatesTheUniqueKeysetAndTTLIndexesAndAppliesANewRetention(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := testDatabase(t, ctx)
	repository := viewmongo.NewHistoryRepository(db)
	if err := repository.EnsureIndexes(ctx, testRetention); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	indexes := indexSpecs(t, ctx, db)
	unique := indexes["user_id_1_post_id_1"]
	if unique == nil || unique.Lookup("unique").Boolean() != true {
		t.Fatalf("indexes = %v, want a unique (user_id, post_id) index", indexes)
	}
	keyset := indexes[viewmongo.UserHistoryIndex]
	wantKeys := bson.D{{Key: "user_id", Value: int32(1)}, {Key: "last_viewed_at", Value: int32(-1)}, {Key: "post_id", Value: int32(-1)}}
	if keyset == nil || !sameKeys(t, keyset.Lookup("key"), wantKeys) {
		t.Fatalf("indexes = %v, want %s on (user_id 1, last_viewed_at -1, post_id -1)", indexes, viewmongo.UserHistoryIndex)
	}
	if got := ttlSeconds(t, indexes); got != int64(testRetention/time.Second) {
		t.Fatalf("expireAfterSeconds = %d, want %d (90 days)", got, int64(testRetention/time.Second))
	}

	// A changed retention is applied in place at the next start.
	if err := repository.EnsureIndexes(ctx, 30*24*time.Hour); err != nil {
		t.Fatalf("EnsureIndexes() with a new retention error = %v", err)
	}
	if got := ttlSeconds(t, indexSpecs(t, ctx, db)); got != int64(30*24*time.Hour/time.Second) {
		t.Fatalf("expireAfterSeconds = %d after the change, want 30 days", got)
	}

	// A retention a TTL index cannot hold is refused before anything is changed.
	for _, retention := range []time.Duration{0, -time.Hour, time.Millisecond, 100 * 365 * 24 * time.Hour} {
		if err := repository.EnsureIndexes(ctx, retention); err == nil {
			t.Fatalf("EnsureIndexes(%s) error = nil, want a rejected retention", retention)
		}
	}

	// Two inserts of one (user, post) are refused by the unique index.
	row := bson.D{{Key: "user_id", Value: "u"}, {Key: "post_id", Value: "p"}, {Key: "last_viewed_at", Value: time.Now().UTC()}}
	if _, err := db.Collection(viewmongo.CollectionName).InsertOne(ctx, row); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if _, err := db.Collection(viewmongo.CollectionName).InsertOne(ctx, row); !mongodriver.IsDuplicateKeyError(err) {
		t.Fatalf("second InsertOne() error = %v, want a duplicate key error", err)
	}
}

func indexSpecs(t *testing.T, ctx context.Context, db *mongodriver.Database) map[string]bson.Raw {
	t.Helper()

	cursor, err := db.Collection(viewmongo.CollectionName).Indexes().List(ctx)
	if err != nil {
		t.Fatalf("Indexes().List() error = %v", err)
	}
	var specs []bson.Raw
	if err := cursor.All(ctx, &specs); err != nil {
		t.Fatalf("All() error = %v", err)
	}
	byName := map[string]bson.Raw{}
	for _, spec := range specs {
		byName[spec.Lookup("name").StringValue()] = spec
	}
	return byName
}

func ttlSeconds(t *testing.T, indexes map[string]bson.Raw) int64 {
	t.Helper()

	ttl := indexes["last_viewed_at_1"]
	if ttl == nil {
		t.Fatalf("indexes = %v, want a TTL index on last_viewed_at", indexes)
	}
	value := ttl.Lookup("expireAfterSeconds")
	if seconds, ok := value.Int32OK(); ok {
		return int64(seconds)
	}
	return value.AsInt64()
}

func sameKeys(t *testing.T, value bson.RawValue, want bson.D) bool {
	t.Helper()

	var got bson.D
	if err := value.Unmarshal(&got); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(got) != len(want) {
		return false
	}
	for index := range want {
		if got[index].Key != want[index].Key || got[index].Value != want[index].Value {
			return false
		}
	}
	return true
}
