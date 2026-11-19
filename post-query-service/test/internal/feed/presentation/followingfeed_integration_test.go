package presentation_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	followusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	followmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/mongo"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
)

// followingPageBody is the JSON of a following feed page.
type followingPageBody struct {
	Items []struct {
		PostID  string `json:"postId"`
		Creator struct {
			UserID   string `json:"userId"`
			Username string `json:"username"`
		} `json:"creator"`
		CreatedAt time.Time `json:"createdAt"`
		Viewer    struct {
			Liked            bool `json:"liked"`
			FollowingCreator bool `json:"followingCreator"`
		} `json:"viewer"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (p followingPageBody) postIDs() []string {
	ids := make([]string, 0, len(p.Items))
	for _, item := range p.Items {
		ids = append(ids, item.PostID)
	}
	return ids
}

func followingTarget(limit int, cursor *string) string {
	target := fmt.Sprintf("/api/feed/following?limit=%d", limit)
	if cursor != nil {
		target += "&cursor=" + url.QueryEscape(*cursor)
	}
	return target
}

func listFollowing(t *testing.T, mux *http.ServeMux, target string) followingPageBody {
	t.Helper()

	recorder := serve(mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var page followingPageBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &page); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return page
}

// pageThroughFollowing follows nextCursor from the first page until it is null.
func pageThroughFollowing(t *testing.T, mux *http.ServeMux, limit int) ([]int, []string) {
	t.Helper()

	var sizes []int
	var served []string
	var cursor *string
	for range 200 {
		page := listFollowing(t, mux, followingTarget(limit, cursor))
		sizes = append(sizes, len(page.Items))
		served = append(served, page.postIDs()...)
		if page.NextCursor == nil {
			return sizes, served
		}
		cursor = page.NextCursor
	}
	t.Fatal("the following feed never ended")
	return nil, nil
}

// followingWorld holds the follow and block repositories, with their indexes, next to a
// feedApp.
type followingWorld struct {
	*feedApp
	follows *followmongo.Repository
	blocks  *blockmongo.Repository
}

func newFollowingWorld(t *testing.T, ctx context.Context) *followingWorld {
	t.Helper()

	app := newFeedApp(t, ctx)
	world := &followingWorld{feedApp: app, follows: followmongo.NewRepository(app.db), blocks: blockmongo.NewRepository(app.db)}
	if err := world.follows.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	if err := world.blocks.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	return world
}

func (w *followingWorld) follow(t *testing.T, ctx context.Context, follower, followed uuid.UUID, at time.Time) {
	t.Helper()

	if err := w.follows.Insert(ctx, followusecase.CreateFollowInput{FollowerUserID: follower, FollowedUserID: followed, CreatedAt: at}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
}

func (w *followingWorld) block(t *testing.T, ctx context.Context, blocker, blocked uuid.UUID) {
	t.Helper()

	if err := w.blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: blocker, BlockedUserID: blocked, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
}

func TestFollowingFeed_PagesNewestFirstAndExcludesBlockedCreators(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	caller := world.viewerID
	ana, bruno := world.registerUser(t, ctx, "ana"), world.registerUser(t, ctx, "bruno")
	blockedByCaller, blockerOfCaller, stranger := world.registerUser(t, ctx, "blocked"), world.registerUser(t, ctx, "blocker"), world.registerUser(t, ctx, "stranger")
	for index, followed := range []uuid.UUID{ana, bruno, blockedByCaller, blockerOfCaller} {
		world.follow(t, ctx, caller, followed, commentsEpoch.Add(time.Duration(index)*time.Minute))
	}
	world.block(t, ctx, caller, blockedByCaller)
	world.block(t, ctx, blockerOfCaller, caller)

	dates := map[string]time.Time{}
	for index := range 7 {
		// Two posts share each date, so the post id breaks the tie.
		createdAt := commentsEpoch.Add(time.Duration(index/2) * time.Hour)
		dates[world.insertPostBy(t, ctx, ana, createdAt).String()] = createdAt
		dates[world.insertPostBy(t, ctx, bruno, createdAt.Add(time.Duration(index%2)*time.Hour)).String()] = createdAt.Add(time.Duration(index%2) * time.Hour)
	}
	// Newer than every followed post, and never served: hidden by a block, not followed,
	// or the caller's own.
	for _, author := range []uuid.UUID{blockedByCaller, blockerOfCaller, stranger, caller} {
		world.insertPostBy(t, ctx, author, commentsEpoch.Add(48*time.Hour))
	}

	sizes, served := pageThroughFollowing(t, world.mux, 4)

	if want := newestFirst(dates); !slices.Equal(served, want) {
		t.Fatalf("served = %v, want only ana's and bruno's posts, newest first, each once: %v", served, want)
	}
	if !slices.Equal(sizes, []int{4, 4, 4, 2}) {
		t.Fatalf("page sizes = %v, want [4 4 4 2] with a null cursor on the last", sizes)
	}

	first := listFollowing(t, world.mux, followingTarget(1, nil)).Items[0]
	wantAuthor := map[bool]uuid.UUID{true: ana, false: bruno}[first.Creator.Username == "ana"]
	if first.PostID != served[0] || first.Creator.UserID != wantAuthor.String() || !first.Viewer.FollowingCreator || !first.CreatedAt.Equal(dates[first.PostID]) {
		t.Fatalf("item = %+v, want the post view of the newest followed post", first)
	}
}

func TestFollowingFeed_AnswersAnEmptyPageWhenTheCallerFollowsNobody(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	// Someone else's follows and posts are not the caller's.
	other := world.registerUser(t, ctx, "other")
	world.follow(t, ctx, other, world.creator, commentsEpoch)
	world.insertPostBy(t, ctx, world.creator, commentsEpoch)

	recorder := serve(world.mux, http.MethodGet, "/api/feed/following", "", nil)

	if recorder.Code != http.StatusOK || strings.TrimSpace(recorder.Body.String()) != `{"items":[],"nextCursor":null}` {
		t.Fatalf("status = %d: %s, want 200 with items [] and nextCursor null", recorder.Code, recorder.Body)
	}
}

func TestFollowingFeed_LeavesTheRecommendedFeedUntouched(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	// The caller has a recommended feed of the creator's posts, and follows the creator.
	postIDs := world.seedPosts(t, ctx, 5)
	world.follow(t, ctx, world.viewerID, world.creator, commentsEpoch)
	sessionBefore := feedSession(t, ctx, world)

	_, served := pageThroughFollowing(t, world.mux, 2)

	if len(served) != len(postIDs) {
		t.Fatalf("served %d posts, want the %d posts of the followed creator", len(served), len(postIDs))
	}
	servedUnits, err := world.db.Collection("feed_units").CountDocuments(ctx, bson.D{{Key: "served_at", Value: bson.D{{Key: "$ne", Value: nil}}}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if servedUnits != 0 {
		t.Fatalf("%d feed units marked served, want the recommended feed untouched", servedUnits)
	}
	// No heartbeat, position, lease or prefetch mark: the session is as the build left it.
	if sessionAfter := feedSession(t, ctx, world); !bytes.Equal(sessionAfter, sessionBefore) {
		t.Fatalf("session = %s, was %s: the following feed must not touch the recommended feed's session", sessionAfter, sessionBefore)
	}
	// The recommended feed still serves every unit from the start.
	if claimed := world.claim(t, "?limit=5"); len(claimed.Items) != len(postIDs) {
		t.Fatalf("claim served %d units, want all %d still unserved", len(claimed.Items), len(postIDs))
	}
}

// feedSession reads the caller's whole user_feed_sessions document.
func feedSession(t *testing.T, ctx context.Context, world *followingWorld) bson.Raw {
	t.Helper()

	session, err := world.db.Collection("user_feed_sessions").FindOne(ctx, bson.D{{Key: "user_id", Value: world.viewerID.String()}}).Raw()
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	return session
}

func TestFollowingFeed_NeverServesAPostTwiceWhenNewPostsArriveWhilePaging(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	ana := world.registerUser(t, ctx, "ana")
	world.follow(t, ctx, world.viewerID, ana, commentsEpoch)
	dates := map[string]time.Time{}
	for index := range 6 {
		createdAt := commentsEpoch.Add(time.Duration(index) * time.Minute)
		dates[world.insertPostBy(t, ctx, ana, createdAt).String()] = createdAt
	}
	want := newestFirst(dates)

	first := listFollowing(t, world.mux, followingTarget(3, nil))
	// Posted between two page requests: an offset would shift the next page back by one.
	world.insertPostBy(t, ctx, ana, commentsEpoch.Add(time.Hour))
	second := listFollowing(t, world.mux, followingTarget(3, first.NextCursor))

	if served := append(first.postIDs(), second.postIDs()...); !slices.Equal(served, want) || second.NextCursor != nil {
		t.Fatalf("served = %v (next %v), want each older post once: %v", served, second.NextCursor, want)
	}
}

func TestFollowingFeed_UsesOnlyTheMostRecentFollowsAboveTheCap(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	oldest, middle, newest := world.registerUser(t, ctx, "oldest"), world.registerUser(t, ctx, "middle"), world.registerUser(t, ctx, "newest")
	for index, followed := range []uuid.UUID{oldest, middle, newest} {
		world.follow(t, ctx, world.viewerID, followed, commentsEpoch.Add(time.Duration(index)*time.Hour))
	}
	middlePost := world.insertPostBy(t, ctx, middle, commentsEpoch)
	newestPost := world.insertPostBy(t, ctx, newest, commentsEpoch.Add(-time.Hour))
	world.insertPostBy(t, ctx, oldest, commentsEpoch.Add(time.Hour))

	mux := http.NewServeMux()
	logger := discardLogger()
	presentation.RegisterRoutes(mux, withSubject(t, world.viewerID.String()), presentation.Routes{
		FollowingFeed: presentation.NewFollowingFeedHandler(usecase.NewFollowingFeed(feedmongo.NewHydrationRepository(world.db), usecase.FollowingFeedConfig{MaxFollows: 2}, logger), unsignedURLs{}, logger),
	})

	page := listFollowing(t, mux, followingTarget(20, nil))

	if want := []string{middlePost.String(), newestPost.String()}; !slices.Equal(page.postIDs(), want) || page.NextCursor != nil {
		t.Fatalf("page = %v, want the posts of the 2 most recently followed users only: %v", page.postIDs(), want)
	}
}

func TestFollowingFeed_RejectsAMalformedCursorOrLimitWith400(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)

	for target, code := range map[string]string{
		"/api/feed/following?cursor=abc": "invalid_cursor",
		"/api/feed/following?limit=0":    "invalid_limit",
		"/api/feed/following?limit=51":   "invalid_limit",
	} {
		recorder := serve(world.mux, http.MethodGet, target, "", nil)
		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
	}
}

// followingKeysetBranches is the keyset ranges of one page query: (C, <I), then (<C).
const followingKeysetBranches = 2

func TestFollowingFeed_EveryPageIsAMergeOfIndexSeeksWhateverTheFollowCount(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	// More follows than one query takes (200), so the ids are split in two queries.
	const followed, postsEach, limit = 250, 2, 20
	dates := map[string]time.Time{}
	var postDocuments []any
	for index := range followed {
		author := world.registerUser(t, ctx, fmt.Sprintf("author_%d", index))
		world.follow(t, ctx, world.viewerID, author, commentsEpoch.Add(time.Duration(index)*time.Second))
		for number := range postsEach {
			// Five authors share each date, so pages start inside ties across both queries.
			createdAt := commentsEpoch.Add(time.Duration((index/5)*postsEach+number) * time.Minute)
			postID := uuid.NewString()
			dates[postID] = createdAt
			postDocuments = append(postDocuments, bson.D{
				{Key: "post_id", Value: postID}, {Key: "user_id", Value: author.String()}, {Key: "post_type", Value: "BASIC"},
				{Key: "media", Value: bson.A{bson.D{{Key: "id", Value: uuid.NewString()}, {Key: "url", Value: "https://media.test/a.jpg"}, {Key: "media_type", Value: "IMAGE"}, {Key: "order", Value: 1}}}},
				{Key: "created_at", Value: createdAt}, {Key: "version", Value: int64(1)},
			})
		}
	}
	if _, err := world.db.Collection("posts").InsertMany(ctx, postDocuments); err != nil {
		t.Fatalf("InsertMany() error = %v", err)
	}

	pages := len(dates) / limit
	measured := map[int]map[string][]profiledQuery{}
	var served []string
	var cursor *string
	for number := 1; number <= pages; number++ {
		profile := number == 1 || number == 2 || number == pages
		if profile {
			restartProfiler(t, ctx, world.db)
		}
		page := listFollowing(t, world.mux, followingTarget(limit, cursor))
		if profile {
			measured[number] = map[string][]profiledQuery{}
			for _, collection := range []string{"posts", "user_follows", "feed_units", "user_feed_sessions"} {
				measured[number][collection] = profiledFinds(t, ctx, world.db, collection)
			}
		}
		if len(page.Items) != limit {
			t.Fatalf("page %d has %d items, want %d", number, len(page.Items), limit)
		}
		served = append(served, page.postIDs()...)
		cursor = page.NextCursor
	}
	if cursor != nil {
		t.Fatalf("the last page has a next cursor %q", *cursor)
	}
	if want := newestFirst(dates); !slices.Equal(served, want) {
		t.Fatal("the pages merged across both queries are not every post once, newest first")
	}

	chunks := (followed + 199) / 200
	keys := map[int]int64{}
	for number, queries := range measured {
		name := fmt.Sprintf("page %d", number)
		posts := queries["posts"]
		if len(posts) == 0 || len(posts) > chunks*followingKeysetBranches {
			t.Fatalf("%s ran %d posts queries, want 1 to %d", name, len(posts), chunks*followingKeysetBranches)
		}
		var documents int64
		for _, query := range posts {
			keys[number] += query.KeysExamined
			documents += query.DocsExamined
			stages, indexes := explainedPlan(t, ctx, world.db, query.Command)
			if slices.ContainsFunc(indexes, func(index string) bool { return index != postmongo.UserPostsIndex }) {
				t.Fatalf("%s posts plan scans %v, want only %s", name, indexes, postmongo.UserPostsIndex)
			}
			if isTieRange(query) {
				// (created_at = C, post_id < I) only matches the posts of the cursor's exact
				// millisecond: the planner may sort those few rows, never more.
				if query.DocsExamined > limit+1 {
					t.Fatalf("%s tie range fetched %d posts, want at most %d", name, query.DocsExamined, limit+1)
				}
				continue
			}
			if query.HasSortStage || slices.Contains(stages, "SORT") || !slices.Contains(stages, "SORT_MERGE") {
				t.Fatalf("%s posts plan = %v (%s), want a SORT_MERGE of index seeks and no SORT", name, stages, query.PlanSummary)
			}
		}
		// Each query fetches at most its limit+1 rows (plus a boundary row per range).
		if bound := int64(chunks * followingKeysetBranches * (limit + 2)); documents > bound {
			t.Fatalf("%s fetched %d posts, want at most %d", name, documents, bound)
		}
		t.Logf("%s: %d posts queries, %d keys and %d documents examined", name, len(posts), keys[number], documents)

		// The hinted read is the followed ids'; the other is the page's viewer state
		// (followingCreator), served by the unique (follower, followed) index.
		follows := slices.DeleteFunc(slices.Clone(queries["user_follows"]), func(query profiledQuery) bool {
			_, err := query.Command.LookupErr("hint")
			return err != nil
		})
		if len(follows) != 1 || follows[0].DocsExamined != 0 {
			t.Fatalf("%s follows reads = %+v, want one read covered by the index", name, follows)
		}
		stages, indexes := assertNoBlockingSort(t, ctx, world.db, name+" follows", follows[0])
		if !slices.Equal(indexes, []string{followmongo.RecentFollowsIndex}) || slices.Contains(stages, "FETCH") {
			t.Fatalf("%s follows plan = %v on %v, want a covered scan of %s", name, stages, indexes, followmongo.RecentFollowsIndex)
		}
		for _, collection := range []string{"feed_units", "user_feed_sessions"} {
			if touched := queries[collection]; len(touched) != 0 {
				t.Fatalf("%s read %s: the following feed must not touch the recommended feed", name, collection)
			}
		}
	}
	// A seek per followed id and range at most, plus the page: a deep page costs what an
	// early one costs, never the rows before it.
	if bound := int64(followed*followingKeysetBranches + chunks*followingKeysetBranches*(limit+2)); keys[pages] > bound || keys[pages] > keys[2]+int64(followed) {
		t.Fatalf("page %d examined %d keys (page 2: %d), want at most %d", pages, keys[pages], keys[2], bound)
	}
}

// profiledFinds reads every find the profiler recorded on collection since
// restartProfiler.
func profiledFinds(t *testing.T, ctx context.Context, db *mongodriver.Database, collection string) []profiledQuery {
	t.Helper()

	filter := bson.D{{Key: "ns", Value: db.Name() + "." + collection}, {Key: "op", Value: "query"}}
	cursor, err := db.Collection("system.profile").Find(ctx, filter, options.Find().SetSort(bson.D{{Key: "ts", Value: 1}}))
	if err != nil {
		t.Fatalf("read the profiler: %v", err)
	}
	var queries []profiledQuery
	if err := cursor.All(ctx, &queries); err != nil {
		t.Fatalf("read the profiler: %v", err)
	}
	return queries
}

// isTieRange tells the keyset range (created_at = C, post_id < I) from (created_at < C)
// and from a first page, which have an operator or nothing on created_at.
func isTieRange(query profiledQuery) bool {
	createdAt, err := query.Command.LookupErr("filter", "created_at")
	return err == nil && createdAt.Type == bson.TypeDateTime
}

// assertNoBlockingSort fails when the query sorted in memory: a SORT stage, as opposed
// to a SORT_MERGE of index scans already in order. It returns the explained plan's
// stages and indexes.
func assertNoBlockingSort(t *testing.T, ctx context.Context, db *mongodriver.Database, name string, query profiledQuery) ([]string, []string) {
	t.Helper()

	stages, indexes := explainedPlan(t, ctx, db, query.Command)
	if query.HasSortStage || slices.Contains(stages, "SORT") {
		t.Fatalf("%s sorted in memory: %s, stages %v", name, query.PlanSummary, stages)
	}
	return stages, indexes
}
