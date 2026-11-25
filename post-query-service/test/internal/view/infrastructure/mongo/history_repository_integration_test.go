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
	CountedDay   string    `bson:"counted_day"`
	CountedViews int       `bson:"counted_views"`
}

// testDailyLimit is the production default of VIEW_COUNT_DAILY_LIMIT_PER_USER.
const testDailyLimit = 5

// historyView builds the repository input the usecase builds: the view's UTC day.
func historyView(userID, postID uuid.UUID, viewedAt time.Time, limit int) usecase.RecordPostViewHistory {
	return usecase.RecordPostViewHistory{UserID: userID, PostID: postID, ViewedAt: viewedAt, Day: viewedAt.UTC().Format("2006-01-02"), DailyLimit: limit}
}

func record(t *testing.T, ctx context.Context, repository *viewmongo.HistoryRepository, view usecase.RecordPostViewHistory) usecase.ViewCountDecision {
	t.Helper()

	decision, err := repository.Record(ctx, view)
	if err != nil {
		t.Fatalf("Record(%+v) error = %v", view, err)
	}
	return decision
}

func rowOf(t *testing.T, ctx context.Context, db *mongodriver.Database, userID, postID uuid.UUID) historyRow {
	t.Helper()

	var row historyRow
	filter := bson.D{{Key: "user_id", Value: userID.String()}, {Key: "post_id", Value: postID.String()}}
	if err := db.Collection(viewmongo.CollectionName).FindOne(ctx, filter).Decode(&row); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	return row
}

func newHistoryRepository(t *testing.T, ctx context.Context) (*viewmongo.HistoryRepository, *mongodriver.Database) {
	t.Helper()

	db := testDatabase(t, ctx)
	repository := viewmongo.NewHistoryRepository(db)
	if err := repository.EnsureIndexes(ctx, testRetention); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	return repository, db
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
		record(t, ctx, repository, historyView(viewer, postID, base.Add(time.Duration(minute)*time.Minute), testDailyLimit))
	}
	record(t, ctx, repository, historyView(other, postID, base, testDailyLimit))

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

func TestHistoryRepository_Record_CountsTheFirstFiveViewsOfAUserAndPostOnADayAndNotTheSixth(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repository, db := newHistoryRepository(t, ctx)
	viewer, other, postID := uuid.New(), uuid.New(), uuid.New()
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	for view := range 7 {
		want := usecase.ViewCounted
		if view >= testDailyLimit {
			want = usecase.ViewOverDailyLimit
		}
		viewedAt := base.Add(time.Duration(view) * time.Minute)
		if got := record(t, ctx, repository, historyView(viewer, postID, viewedAt, testDailyLimit)); got != want {
			t.Fatalf("view %d: Record() = %v, want %v", view+1, got, want)
		}
		// Every view moves the history entry, counted or not.
		if row := rowOf(t, ctx, db, viewer, postID); !row.LastViewedAt.Equal(viewedAt) {
			t.Fatalf("view %d: last_viewed_at = %v, want %v", view+1, row.LastViewedAt, viewedAt)
		}
	}
	if row := rowOf(t, ctx, db, viewer, postID); row.CountedDay != "2026-03-01" || row.CountedViews != testDailyLimit {
		t.Fatalf("row = %+v, want counted_day 2026-03-01 and counted_views %d", row, testDailyLimit)
	}

	// Another user has their own five views of the same post.
	for view := range testDailyLimit {
		if got := record(t, ctx, repository, historyView(other, postID, base.Add(time.Duration(view)*time.Second), testDailyLimit)); got != usecase.ViewCounted {
			t.Fatalf("other user's view %d: Record() = %v, want counted", view+1, got)
		}
	}
	if rows := rowsOf(t, ctx, db, viewer); len(rows) != 1 {
		t.Fatalf("viewer rows = %d, want one document per user and post, not per view", len(rows))
	}
}

func TestHistoryRepository_Record_UsesTheLimitItIsGiven(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repository, _ := newHistoryRepository(t, ctx)
	viewer, postID := uuid.New(), uuid.New()
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	for view, want := range []usecase.ViewCountDecision{usecase.ViewCounted, usecase.ViewCounted, usecase.ViewCounted, usecase.ViewOverDailyLimit} {
		if got := record(t, ctx, repository, historyView(viewer, postID, base.Add(time.Duration(view)*time.Minute), 3)); got != want {
			t.Fatalf("view %d with limit 3: Record() = %v, want %v", view+1, got, want)
		}
	}
}

func TestHistoryRepository_Record_CountsAgainOnTheNextUTCDayAndDoesNotResetOnALateEventFromThePreviousDay(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repository, db := newHistoryRepository(t, ctx)
	viewer, postID := uuid.New(), uuid.New()
	dayOne := time.Date(2026, 3, 1, 23, 0, 0, 0, time.UTC)
	dayTwo := time.Date(2026, 3, 2, 0, 0, 0, 0, time.UTC)

	for view := range testDailyLimit + 1 {
		record(t, ctx, repository, historyView(viewer, postID, dayOne.Add(time.Duration(view)*time.Minute), testDailyLimit))
	}
	// Midnight UTC starts a new day, five more views.
	for view := range testDailyLimit + 1 {
		want := usecase.ViewCounted
		if view >= testDailyLimit {
			want = usecase.ViewOverDailyLimit
		}
		if got := record(t, ctx, repository, historyView(viewer, postID, dayTwo.Add(time.Duration(view)*time.Minute), testDailyLimit)); got != want {
			t.Fatalf("day two view %d: Record() = %v, want %v", view+1, got, want)
		}
	}

	// A late view of day one, at a local (UTC+2) time that already reads as day two.
	late := time.Date(2026, 3, 2, 1, 30, 0, 0, time.FixedZone("UTC+2", 2*60*60))
	if got := record(t, ctx, repository, historyView(viewer, postID, late, testDailyLimit)); got != usecase.ViewFromPastDay {
		t.Fatalf("late view: Record() = %v, want %v", got, usecase.ViewFromPastDay)
	}
	row := rowOf(t, ctx, db, viewer, postID)
	if row.CountedDay != "2026-03-02" || row.CountedViews != testDailyLimit {
		t.Fatalf("row = %+v, want day two's count kept (2026-03-02, %d)", row, testDailyLimit)
	}
	if want := dayTwo.Add(time.Duration(testDailyLimit) * time.Minute); !row.LastViewedAt.Equal(want) {
		t.Fatalf("last_viewed_at = %v, want %v: an older view never moves it back", row.LastViewedAt, want)
	}

	// A late view of a row whose first view was on a later day is not counted either.
	otherPost := uuid.New()
	record(t, ctx, repository, historyView(viewer, otherPost, dayTwo, testDailyLimit))
	if got := record(t, ctx, repository, historyView(viewer, otherPost, dayOne, testDailyLimit)); got != usecase.ViewFromPastDay {
		t.Fatalf("late view of another post: Record() = %v, want %v", got, usecase.ViewFromPastDay)
	}
}

func TestHistoryRepository_Record_TreatsAHistoryDocumentWithoutCountedDayAsNoViewsCounted(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repository, db := newHistoryRepository(t, ctx)
	viewer, postID := uuid.New(), uuid.New()
	stored := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	legacy := bson.D{{Key: "user_id", Value: viewer.String()}, {Key: "post_id", Value: postID.String()}, {Key: "last_viewed_at", Value: stored}}
	if _, err := db.Collection(viewmongo.CollectionName).InsertOne(ctx, legacy); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}

	// A view of the same day as the legacy row's last view starts that day at 1.
	if got := record(t, ctx, repository, historyView(viewer, postID, stored.Add(-time.Hour), testDailyLimit)); got != usecase.ViewCounted {
		t.Fatalf("Record() = %v, want the first view after the upgrade counted", got)
	}
	row := rowOf(t, ctx, db, viewer, postID)
	if row.CountedDay != "2026-03-01" || row.CountedViews != 1 || !row.LastViewedAt.Equal(stored) {
		t.Fatalf("row = %+v, want counted_day 2026-03-01, counted_views 1 and last_viewed_at kept at %v", row, stored)
	}
}

func TestHistoryRepository_Record_RejectsAnInputItCannotCountWithoutWriting(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	repository, db := newHistoryRepository(t, ctx)
	viewedAt := time.Date(2026, 3, 1, 23, 30, 0, 0, time.UTC)
	valid := historyView(uuid.New(), uuid.New(), viewedAt, testDailyLimit)
	cases := map[string]func(*usecase.RecordPostViewHistory){
		"no user":         func(view *usecase.RecordPostViewHistory) { view.UserID = uuid.Nil },
		"no post":         func(view *usecase.RecordPostViewHistory) { view.PostID = uuid.Nil },
		"no view time":    func(view *usecase.RecordPostViewHistory) { view.ViewedAt = time.Time{} },
		"no day":          func(view *usecase.RecordPostViewHistory) { view.Day = "" },
		"not the UTC day": func(view *usecase.RecordPostViewHistory) { view.Day = "2026-03-02" },
		"other layout":    func(view *usecase.RecordPostViewHistory) { view.Day = "01/03/2026" },
		"limit zero":      func(view *usecase.RecordPostViewHistory) { view.DailyLimit = 0 },
		"negative limit":  func(view *usecase.RecordPostViewHistory) { view.DailyLimit = -1 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			view := valid
			mutate(&view)
			if _, err := repository.Record(ctx, view); err == nil {
				t.Fatalf("Record(%+v) error = nil, want a rejected input", view)
			}
		})
	}
	count, err := db.Collection(viewmongo.CollectionName).CountDocuments(ctx, bson.D{})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("view_history documents = %d, want 0", count)
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
