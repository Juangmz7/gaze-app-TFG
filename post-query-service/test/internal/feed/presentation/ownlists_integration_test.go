package presentation_test

import (
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

	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	likeusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
	viewusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
	viewmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/mongo"
)

// ownPageBody is the JSON of an own tab page, decoded field by field so a renamed field
// fails the test. At is likedAt or viewedAt, whichever the tab answers.
type ownPageBody struct {
	Items []struct {
		PostID            string     `json:"postId"`
		CoverThumbnailURL *string    `json:"coverThumbnailUrl"`
		MediaType         string     `json:"mediaType"`
		MediaCount        int        `json:"mediaCount"`
		PostType          string     `json:"postType"`
		CollabStatus      *string    `json:"collabStatus"`
		LikeCount         int64      `json:"likeCount"`
		CreatedAt         time.Time  `json:"createdAt"`
		LikedAt           *time.Time `json:"likedAt"`
		ViewedAt          *time.Time `json:"viewedAt"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (p ownPageBody) postIDs() []string {
	ids := make([]string, 0, len(p.Items))
	for _, item := range p.Items {
		ids = append(ids, item.PostID)
	}
	return ids
}

func listOwn(t *testing.T, mux *http.ServeMux, target string) ownPageBody {
	t.Helper()

	recorder := serve(mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var page ownPageBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &page); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return page
}

func ownTarget(tab string, limit int, cursor *string) string {
	target := fmt.Sprintf("/api/content/me/%s?limit=%d", tab, limit)
	if cursor != nil {
		target += "&cursor=" + url.QueryEscape(*cursor)
	}
	return target
}

// pageThroughOwn follows nextCursor from the first page of a tab until it is null.
func pageThroughOwn(t *testing.T, mux *http.ServeMux, tab string, limit int) ([]int, ownPageBody) {
	t.Helper()

	var sizes []int
	var all ownPageBody
	var cursor *string
	for range 100 {
		page := listOwn(t, mux, ownTarget(tab, limit, cursor))
		sizes = append(sizes, len(page.Items))
		all.Items = append(all.Items, page.Items...)
		if page.NextCursor == nil {
			return sizes, all
		}
		cursor = page.NextCursor
	}
	t.Fatalf("%s tab never ended", tab)
	return nil, ownPageBody{}
}

func (a *feedApp) like(t *testing.T, ctx context.Context, userID, postID uuid.UUID, likedAt time.Time) {
	t.Helper()

	if _, err := a.likes.Insert(ctx, likeusecase.RecordLikeInput{PostID: postID, UserID: userID, CreatedAt: likedAt}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
}

func (a *feedApp) view(t *testing.T, ctx context.Context, userID, postID uuid.UUID, viewedAt time.Time) {
	t.Helper()

	view := viewusecase.RecordPostViewHistory{UserID: userID, PostID: postID, ViewedAt: viewedAt, Day: viewedAt.UTC().Format("2006-01-02"), DailyLimit: 5}
	if _, err := a.history.Record(ctx, view); err != nil {
		t.Fatalf("Record() error = %v", err)
	}
}

func TestListMyLiked_ReturnsTheCallersLikesNewestFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	blocks := blockmongo.NewRepository(app.db)
	if err := blocks.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	other, blocker := app.registerUser(t, ctx, "bruno"), app.registerUser(t, ctx, "blocker")

	likedAt := map[string]time.Time{}
	for index := range 7 {
		// The posts are created newest first but liked oldest first, and likes share
		// dates two by two, so the like's date and then the post id order the tab.
		postID := app.insertPostBy(t, ctx, app.creator, commentsEpoch.Add(-time.Duration(index)*time.Hour))
		at := commentsEpoch.Add(time.Duration(index/2) * time.Minute)
		app.like(t, ctx, app.viewerID, postID, at)
		likedAt[postID.String()] = at
	}
	// Liked by someone else only; liked by the caller then deleted; liked by the caller
	// but by a creator who blocked the caller.
	app.like(t, ctx, other, app.insertPostBy(t, ctx, app.creator, commentsEpoch), commentsEpoch.Add(time.Hour))
	deleted := app.insertPostBy(t, ctx, app.creator, commentsEpoch)
	app.like(t, ctx, app.viewerID, deleted, commentsEpoch.Add(2*time.Hour))
	if err := app.posts.Delete(ctx, deleted); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}
	hidden := app.insertPostBy(t, ctx, blocker, commentsEpoch)
	app.like(t, ctx, app.viewerID, hidden, commentsEpoch.Add(3*time.Hour))
	if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: blocker, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	sizes, all := pageThroughOwn(t, app.mux, "liked", 3)

	if want := newestFirst(likedAt); !slices.Equal(all.postIDs(), want) {
		t.Fatalf("served = %v\nwant     %v: the caller's likes newest first, deleted and hidden posts skipped", all.postIDs(), want)
	}
	if !slices.Equal(sizes, []int{3, 3, 1}) {
		t.Fatalf("page sizes = %v, want [3 3 1]: full pages despite the skipped posts", sizes)
	}
	for _, item := range all.Items {
		if item.LikedAt == nil || !item.LikedAt.Equal(likedAt[item.PostID]) || item.ViewedAt != nil {
			t.Fatalf("item %s likedAt = %v, viewedAt = %v, want the like's date and no viewedAt", item.PostID, item.LikedAt, item.ViewedAt)
		}
		if item.MediaType != "IMAGE" || item.MediaCount != 2 || item.CoverThumbnailURL == nil || item.PostType != "BASIC" || item.CollabStatus != nil {
			t.Fatalf("item = %+v, want the GridItem of the post", item)
		}
	}
}

func TestListMyHistory_ReturnsOneEntryPerPostMostRecentFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	often, once, twice := app.insertPostBy(t, ctx, app.creator, commentsEpoch), app.insertPostBy(t, ctx, app.creator, commentsEpoch), app.insertPostBy(t, ctx, app.creator, commentsEpoch)

	// The same post viewed ten times, the latest view delivered before two older ones.
	for _, minute := range []int{0, 1, 2, 3, 4, 5, 6, 9, 7, 8} {
		app.view(t, ctx, app.viewerID, often, commentsEpoch.Add(time.Duration(minute)*time.Minute))
	}
	app.view(t, ctx, app.viewerID, once, commentsEpoch.Add(5*time.Minute))
	app.view(t, ctx, app.viewerID, twice, commentsEpoch.Add(20*time.Minute))
	app.view(t, ctx, app.viewerID, twice, commentsEpoch.Add(3*time.Minute))

	sizes, all := pageThroughOwn(t, app.mux, "history", 2)

	want := []string{twice.String(), often.String(), once.String()}
	if !slices.Equal(all.postIDs(), want) {
		t.Fatalf("served = %v, want %v: one entry per post, most recent view first", all.postIDs(), want)
	}
	if !slices.Equal(sizes, []int{2, 1}) {
		t.Fatalf("page sizes = %v, want [2 1]", sizes)
	}
	wantViewed := []time.Time{commentsEpoch.Add(20 * time.Minute), commentsEpoch.Add(9 * time.Minute), commentsEpoch.Add(5 * time.Minute)}
	for index, item := range all.Items {
		if item.ViewedAt == nil || !item.ViewedAt.Equal(wantViewed[index]) || item.LikedAt != nil {
			t.Fatalf("item %d viewedAt = %v, likedAt = %v, want the latest view %v", index, item.ViewedAt, item.LikedAt, wantViewed[index])
		}
	}
}

func TestListMyHistory_DoesNotExposeTheDailyViewCount(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPostBy(t, ctx, app.creator, commentsEpoch)
	for minute := range 7 {
		app.view(t, ctx, app.viewerID, postID, commentsEpoch.Add(time.Duration(minute)*time.Minute))
	}

	recorder := serve(app.mux, http.MethodGet, ownTarget("history", 20, nil), "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET history status = %d: %s", recorder.Code, recorder.Body)
	}
	var body struct {
		Items []map[string]json.RawMessage `json:"items"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(body.Items) != 1 {
		t.Fatalf("items = %d, want 1", len(body.Items))
	}
	for key := range body.Items[0] {
		if strings.Contains(strings.ToLower(key), "counted") {
			t.Fatalf("history item has key %q: the daily view count must not be exposed", key)
		}
	}
}

func TestOwnLists_EachUserOnlyEverSeesTheirOwnLists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	ana, bruno := app.viewerID, app.registerUser(t, ctx, "bruno")
	anaPost, brunoPost, shared := app.insertPostBy(t, ctx, app.creator, commentsEpoch), app.insertPostBy(t, ctx, app.creator, commentsEpoch), app.insertPostBy(t, ctx, app.creator, commentsEpoch)
	app.like(t, ctx, ana, anaPost, commentsEpoch)
	app.like(t, ctx, bruno, brunoPost, commentsEpoch)
	app.like(t, ctx, bruno, shared, commentsEpoch.Add(time.Minute))
	app.view(t, ctx, ana, anaPost, commentsEpoch)
	app.view(t, ctx, ana, shared, commentsEpoch.Add(time.Minute))
	app.view(t, ctx, bruno, brunoPost, commentsEpoch.Add(time.Hour))

	cases := []struct {
		caller uuid.UUID
		tab    string
		want   []string
	}{
		{ana, "liked", []string{anaPost.String()}},
		{bruno, "liked", []string{shared.String(), brunoPost.String()}},
		{ana, "history", []string{shared.String(), anaPost.String()}},
		{bruno, "history", []string{brunoPost.String()}},
	}
	for _, tc := range cases {
		mux := app.muxFor(t, tc.caller)
		// A userId in the query is not a way to read someone else's list.
		other := bruno
		if tc.caller == bruno {
			other = ana
		}
		page := listOwn(t, mux, ownTarget(tc.tab, 20, nil)+"&userId="+other.String())
		if !slices.Equal(page.postIDs(), tc.want) {
			t.Fatalf("%s's %s = %v, want only their own %v", tc.caller, tc.tab, page.postIDs(), tc.want)
		}
	}

	// A user with no likes and no views gets empty lists, not someone else's.
	stranger := app.muxFor(t, uuid.New())
	for _, tab := range []string{"liked", "history"} {
		if page := listOwn(t, stranger, ownTarget(tab, 20, nil)); len(page.Items) != 0 || page.NextCursor != nil {
			t.Fatalf("stranger's %s = %v, want an empty list", tab, page.postIDs())
		}
	}
}

func TestOwnLists_RejectAMalformedCursorOrLimitWith400(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)

	for target, code := range map[string]string{
		"/api/content/me/liked?cursor=abc":    "invalid_cursor",
		"/api/content/me/history?limit=0":     "invalid_limit",
		"/api/content/me/history?limit=51":    "invalid_limit",
		"/api/content/me/liked?cursor=%20%20": "invalid_cursor",
	} {
		recorder := serve(app.mux, http.MethodGet, target, "", nil)
		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
	}
}

func TestOwnLists_EveryPageIsAnIndexSeek(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	const total, limit = 300, 20
	for index := range total {
		// Ten rows share each date, so most pages start inside a tie.
		at := commentsEpoch.Add(time.Duration(index/10) * time.Minute)
		postID := app.insertPostBy(t, ctx, app.creator, commentsEpoch)
		app.like(t, ctx, app.viewerID, postID, at)
		app.view(t, ctx, app.viewerID, postID, at)
	}

	for tab, index := range map[string]string{"liked": likemongo.UserLikesIndex, "history": viewmongo.UserHistoryIndex} {
		t.Run(tab, func(t *testing.T) {
			collection := map[string]string{"liked": "post_likes", "history": viewmongo.CollectionName}[tab]
			measured := map[int][]profiledQuery{}
			var cursor *string
			for number := 1; number <= total/limit; number++ {
				profile := number == 1 || number == 2 || number == total/limit
				if profile {
					restartProfiler(t, ctx, app.db)
				}
				page := listOwn(t, app.mux, ownTarget(tab, limit, cursor))
				if profile {
					measured[number] = pageQueries(t, ctx, app.db, collection)
				}
				if len(page.Items) != limit {
					t.Fatalf("page %d has %d items, want %d", number, len(page.Items), limit)
				}
				cursor = page.NextCursor
			}
			if cursor != nil {
				t.Fatalf("the last page has a next cursor %q", *cursor)
			}

			keys := map[int]int64{}
			for number, queries := range measured {
				keys[number] = assertIndexSeeks(t, ctx, app.db, fmt.Sprintf("%s page %d", tab, number), queries, index, gridKeysetBranches, limit)
			}
			if last := total / limit; keys[last] > keys[2]+gridKeysetBranches {
				t.Fatalf("page %d examined %d keys, page 2 %d: a deep page must cost what an early one costs", last, keys[last], keys[2])
			}
		})
	}
}
