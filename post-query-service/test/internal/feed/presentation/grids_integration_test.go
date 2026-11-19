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
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	shareusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
	sharemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/mongo"
)

// gridPageBody is the JSON of a grid page, decoded field by field so a renamed field
// fails the test.
type gridPageBody struct {
	Items []struct {
		PostID            string    `json:"postId"`
		CoverThumbnailURL *string   `json:"coverThumbnailUrl"`
		MediaType         string    `json:"mediaType"`
		MediaCount        int       `json:"mediaCount"`
		PostType          string    `json:"postType"`
		CollabStatus      *string   `json:"collabStatus"`
		LikeCount         int64     `json:"likeCount"`
		CreatedAt         time.Time `json:"createdAt"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (p gridPageBody) postIDs() []string {
	ids := make([]string, 0, len(p.Items))
	for _, item := range p.Items {
		ids = append(ids, item.PostID)
	}
	return ids
}

func (a *feedApp) listGrid(t *testing.T, target string) gridPageBody {
	t.Helper()

	recorder := serve(a.mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var page gridPageBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &page); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return page
}

func gridTarget(userID uuid.UUID, grid string, limit int, cursor *string) string {
	target := fmt.Sprintf("/api/content/users/%s/%s?limit=%d", userID, grid, limit)
	if cursor != nil {
		target += "&cursor=" + url.QueryEscape(*cursor)
	}
	return target
}

// insertPostBy projects a post of userID created at createdAt, with an image first.
func (a *feedApp) insertPostBy(t *testing.T, ctx context.Context, userID uuid.UUID, createdAt time.Time) uuid.UUID {
	t.Helper()

	postID := uuid.New()
	if err := a.posts.Insert(ctx, postusecase.CreatePostInput{
		PostID: postID, UserID: userID, PostType: "BASIC", Description: "Harbour at dusk",
		Media: sampleMedia(), CreatedAt: createdAt, UpdatedAt: createdAt,
	}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	return postID
}

func (a *feedApp) share(t *testing.T, ctx context.Context, userID, postID uuid.UUID, sharedAt time.Time) {
	t.Helper()

	if _, err := a.shares.Insert(ctx, shareusecase.RecordShareInput{PostID: postID, UserID: userID, CreatedAt: sharedAt}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
}

// pageThrough follows nextCursor from the first page until it is null.
func (a *feedApp) pageThrough(t *testing.T, userID uuid.UUID, grid string, limit int) ([]int, []string) {
	t.Helper()

	var sizes []int
	var served []string
	var cursor *string
	for range 100 {
		page := a.listGrid(t, gridTarget(userID, grid, limit, cursor))
		sizes = append(sizes, len(page.Items))
		served = append(served, page.postIDs()...)
		if page.NextCursor == nil {
			return sizes, served
		}
		cursor = page.NextCursor
	}
	t.Fatalf("%s grid never ended", grid)
	return nil, nil
}

// newestFirst sorts ids the way both grids do: by date, then by id, both descending.
func newestFirst(dates map[string]time.Time) []string {
	ids := make([]string, 0, len(dates))
	for id := range dates {
		ids = append(ids, id)
	}
	slices.SortFunc(ids, func(a, b string) int {
		if c := dates[b].Compare(dates[a]); c != 0 {
			return c
		}
		return strings.Compare(b, a)
	})
	return ids
}

func TestListUserPosts_Pages45PostsAs20_20_5NewestFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	owner := app.registerUser(t, ctx, "marta")
	dates := map[string]time.Time{}
	for index := range 45 {
		// Three posts share each date, so the post id breaks the ties.
		createdAt := commentsEpoch.Add(time.Duration(index/3) * time.Minute)
		dates[app.insertPostBy(t, ctx, owner, createdAt).String()] = createdAt
	}
	app.insertPostBy(t, ctx, app.creator, commentsEpoch.Add(time.Hour))

	sizes, served := app.pageThrough(t, owner, "posts", 20)

	if !slices.Equal(sizes, []int{20, 20, 5}) {
		t.Fatalf("page sizes = %v, want [20 20 5] with nextCursor null on the last", sizes)
	}
	if want := newestFirst(dates); !slices.Equal(served, want) {
		t.Fatalf("served = %v\nwant     %v: every post of the user once, newest first", served, want)
	}

	first := app.listGrid(t, gridTarget(owner, "posts", 1, nil)).Items[0]
	if first.MediaType != "IMAGE" || first.MediaCount != 2 || first.PostType != "BASIC" || first.CollabStatus != nil ||
		first.CoverThumbnailURL == nil || *first.CoverThumbnailURL != "https://media.test/a.jpg" || !first.CreatedAt.Equal(dates[first.PostID]) {
		t.Fatalf("first item = %+v, want the first media's type and url, 2 media, BASIC, no collab", first)
	}
}

func TestListUserReposts_SkipsDeletedPostsNewestShareFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	sharer := app.registerUser(t, ctx, "marta")
	shared := make([]uuid.UUID, 0, 6)
	for index := range 6 {
		postID := app.insertPostBy(t, ctx, app.creator, commentsEpoch.Add(-time.Duration(index)*time.Hour))
		// The oldest post is shared last, so the share date orders the grid, not the post's.
		app.share(t, ctx, sharer, postID, commentsEpoch.Add(time.Duration(index)*time.Minute))
		shared = append(shared, postID)
	}
	app.share(t, ctx, app.creator, shared[0], commentsEpoch.Add(time.Hour))
	for _, deleted := range []uuid.UUID{shared[5], shared[2]} {
		if err := app.posts.Delete(ctx, deleted); err != nil {
			t.Fatalf("Delete() error = %v", err)
		}
	}

	sizes, served := app.pageThrough(t, sharer, "reposts", 2)

	want := []string{shared[4].String(), shared[3].String(), shared[1].String(), shared[0].String()}
	if !slices.Equal(served, want) {
		t.Fatalf("served = %v, want %v: newest share first, deleted posts skipped", served, want)
	}
	if !slices.Equal(sizes, []int{2, 2}) {
		t.Fatalf("page sizes = %v, want [2 2]: a page reads on past the deleted posts", sizes)
	}
	page := app.listGrid(t, gridTarget(sharer, "reposts", 1, nil))
	if !page.Items[0].CreatedAt.Equal(commentsEpoch.Add(-4 * time.Hour)) {
		t.Fatalf("createdAt = %v, want the post's creation date", page.Items[0].CreatedAt)
	}
}

func TestUserGrids_Answer404UserNotFoundForAnUnknownDeletedOrBlockRelatedUser(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	blocks := blockmongo.NewRepository(app.db)
	if err := blocks.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	cases := map[string]func(t *testing.T) uuid.UUID{
		"unknown": func(*testing.T) uuid.UUID { return uuid.New() },
		"deleted": func(t *testing.T) uuid.UUID {
			userID := app.registerUser(t, ctx, "gone")
			app.insertPostBy(t, ctx, userID, commentsEpoch)
			if err := app.users.Delete(ctx, userID); err != nil {
				t.Fatalf("Delete() error = %v", err)
			}
			return userID
		},
		"the target blocked the caller": func(t *testing.T) uuid.UUID {
			userID := app.registerUser(t, ctx, "blocker")
			app.insertPostBy(t, ctx, userID, commentsEpoch)
			if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: userID, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			return userID
		},
		"the caller blocked the target": func(t *testing.T) uuid.UUID {
			userID := app.registerUser(t, ctx, "blocked")
			app.insertPostBy(t, ctx, userID, commentsEpoch)
			if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.viewerID, BlockedUserID: userID, CreatedAt: time.Now().UTC()}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			return userID
		},
	}

	for name, hide := range cases {
		t.Run(name, func(t *testing.T) {
			userID := hide(t)
			for _, grid := range []string{"posts", "reposts"} {
				recorder := serve(app.mux, http.MethodGet, gridTarget(userID, grid, 20, nil), "", nil)
				assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "user_not_found")
			}
		})
	}
}

func TestListUserReposts_HidesPostsWhoseCreatorHasABlockWithTheCaller(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	blocks := blockmongo.NewRepository(app.db)
	if err := blocks.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	sharer, blocker := app.registerUser(t, ctx, "marta"), app.registerUser(t, ctx, "blocker")
	visible := app.insertPostBy(t, ctx, app.creator, commentsEpoch)
	hidden := app.insertPostBy(t, ctx, blocker, commentsEpoch)
	app.share(t, ctx, sharer, visible, commentsEpoch)
	app.share(t, ctx, sharer, hidden, commentsEpoch.Add(time.Minute))
	if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: blocker, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	page := app.listGrid(t, gridTarget(sharer, "reposts", 20, nil))

	if !slices.Equal(page.postIDs(), []string{visible.String()}) || page.NextCursor != nil {
		t.Fatalf("page = %v, want only the post GetPost would serve the caller", page.postIDs())
	}
}

func TestUserGrids_RejectAMalformedUserIDCursorOrLimitWith400(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)

	for target, code := range map[string]string{
		"/api/content/users/not-a-uuid/posts":                                "invalid_user_id",
		"/api/content/users/" + app.creator.String() + "/reposts?cursor=abc": "invalid_cursor",
		"/api/content/users/" + app.creator.String() + "/posts?limit=0":      "invalid_limit",
	} {
		recorder := serve(app.mux, http.MethodGet, target, "", nil)
		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
	}
}

// gridKeysetBranches is the keyset ranges of a grid page: (C, <I), then (<C).
const gridKeysetBranches = 2

func TestUserGrids_EveryPageIsAnIndexSeek(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	owner := app.registerUser(t, ctx, "marta")
	const total, limit = 300, 20
	for index := range total {
		// Ten rows share each date, so most pages start inside a tie.
		createdAt := commentsEpoch.Add(time.Duration(index/10) * time.Minute)
		postID := app.insertPostBy(t, ctx, owner, createdAt)
		app.share(t, ctx, owner, postID, createdAt)
	}

	for grid, index := range map[string]string{"posts": postmongo.UserPostsIndex, "reposts": sharemongo.UserSharesIndex} {
		t.Run(grid, func(t *testing.T) {
			collection := map[string]string{"posts": "posts", "reposts": "post_shares"}[grid]
			measured := map[int][]profiledQuery{}
			var cursor *string
			for number := 1; number <= total/limit; number++ {
				profile := number == 1 || number == 2 || number == total/limit
				if profile {
					restartProfiler(t, ctx, app.db)
				}
				page := app.listGrid(t, gridTarget(owner, grid, limit, cursor))
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
				keys[number] = assertIndexSeeks(t, ctx, app.db, fmt.Sprintf("%s page %d", grid, number), queries, index, gridKeysetBranches, limit)
			}
			if last := total / limit; keys[last] > keys[2]+gridKeysetBranches {
				t.Fatalf("page %d examined %d keys, page 2 %d: a deep page must cost what an early one costs", last, keys[last], keys[2])
			}
		})
	}
}
