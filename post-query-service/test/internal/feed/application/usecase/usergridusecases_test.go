package usecase_test

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeGridStore is the read models of the profile grids. Its page queries return the
// rows they were given, starting after the cursor's row, in order: sorting and the
// keyset ranges are MongoDB's job and are tested against it
// (test/internal/feed/presentation/grids_integration_test.go).
type fakeGridStore struct {
	users   map[string]usecase.FeedCreator
	blocked []string
	posts   map[string]usecase.FeedPost
	rows    []usecase.FeedPost
	shares  []usecase.PostRef
	collabs []usecase.FeedCollab
	err     error

	calls        map[string]int
	queries      []usecase.UserGridQuery
	postLookups  [][]string
	collabLookup []string
}

func newFakeGridStore() *fakeGridStore {
	return &fakeGridStore{users: map[string]usecase.FeedCreator{}, posts: map[string]usecase.FeedPost{}, calls: map[string]int{}}
}

func (f *fakeGridStore) FindCreators(_ context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	f.calls["users"]++
	var found []usecase.FeedCreator
	for _, id := range userIDs {
		if user, ok := f.users[id]; ok {
			found = append(found, user)
		}
	}
	return found, f.err
}

func (f *fakeGridStore) FindAllBlockRelatedUserIDs(context.Context, string) ([]string, error) {
	f.calls["blocks"]++
	return f.blocked, nil
}

func (f *fakeGridStore) FindCollabs(_ context.Context, _, postIDs []string) ([]usecase.FeedCollab, error) {
	f.calls["collabs"]++
	f.collabLookup = postIDs
	return f.collabs, nil
}

func (f *fakeGridStore) FindPosts(_ context.Context, postIDs []string) ([]usecase.FeedPost, error) {
	f.calls["posts"]++
	f.postLookups = append(f.postLookups, postIDs)
	var found []usecase.FeedPost
	for _, id := range postIDs {
		if post, ok := f.posts[id]; ok {
			found = append(found, post)
		}
	}
	return found, nil
}

func (f *fakeGridStore) FindUserPosts(_ context.Context, query usecase.UserGridQuery) ([]usecase.FeedPost, error) {
	f.calls["page"]++
	f.queries = append(f.queries, query)
	start := 0
	if query.After != nil {
		start = slices.IndexFunc(f.rows, func(post usecase.FeedPost) bool { return post.PostID == query.After.PostID }) + 1
	}
	return f.rows[start:min(len(f.rows), start+query.Limit)], nil
}

func (f *fakeGridStore) FindUserShares(_ context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	f.calls["page"]++
	f.queries = append(f.queries, query)
	start := 0
	if query.After != nil {
		start = slices.IndexFunc(f.shares, func(share usecase.PostRef) bool { return share.PostID == query.After.PostID }) + 1
	}
	return f.shares[start:min(len(f.shares), start+query.Limit)], nil
}

var gridEpoch = time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

// gridPostOf is a post by creatorID with one image, created index minutes before
// gridEpoch, so a higher index is older.
func gridPostOf(creatorID string, index int) usecase.FeedPost {
	return usecase.FeedPost{
		PostID:    uuid.NewString(),
		CreatorID: creatorID,
		PostType:  usecase.FeedPostTypeBasic,
		Media:     []usecase.FeedMedia{{ID: "m", URL: "a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 1}},
		Stats:     usecase.FeedStats{LikeCount: int64(index)},
		CreatedAt: gridEpoch.Add(-time.Duration(index) * time.Minute),
	}
}

// seedTarget stores the profile owner and returns a grid input of a viewer asking for it.
func seedTarget(store *fakeGridStore, limit int) usecase.UserGridInput {
	input := usecase.UserGridInput{UserID: uuid.New(), TargetUserID: uuid.New(), Limit: limit, CorrelationID: "corr-1"}
	store.users[input.TargetUserID.String()] = usecase.FeedCreator{UserID: input.TargetUserID.String(), Username: "ana"}
	return input
}

func gridPostIDs(page usecase.GridPage) []string {
	ids := make([]string, 0, len(page.Items))
	for _, item := range page.Items {
		ids = append(ids, item.PostID)
	}
	return ids
}

func TestListUserPostsUsecase_Execute_Pages45PostsAs20_20_5WithANullCursorOnTheLast(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 20)
	for index := range 45 {
		store.rows = append(store.rows, gridPostOf(input.TargetUserID.String(), index))
	}
	uc := usecase.NewListUserPosts(store, discardLogger())

	var sizes []int
	var served []string
	for range 3 {
		page, err := uc.Execute(context.Background(), input)
		if err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
		sizes = append(sizes, len(page.Items))
		served = append(served, gridPostIDs(page)...)
		if page.NextCursor == nil {
			break
		}
		after, err := usecase.DecodeGridCursor(*page.NextCursor)
		if err != nil {
			t.Fatalf("DecodeGridCursor() error = %v", err)
		}
		last := page.Items[len(page.Items)-1]
		if after.PostID != last.PostID || !after.CreatedAt.Equal(last.CreatedAt) {
			t.Fatalf("cursor = %+v, want the last item's (createdAt, postId)", after)
		}
		input.After = &after
	}

	if !slices.Equal(sizes, []int{20, 20, 5}) {
		t.Fatalf("page sizes = %v, want [20 20 5] and a null cursor on the last", sizes)
	}
	for index, post := range store.rows {
		if served[index] != post.PostID {
			t.Fatalf("item %d = %s, want %s: every post once, newest first", index, served[index], post.PostID)
		}
	}
	for _, query := range store.queries {
		if query.Limit != 21 || query.UserID != input.TargetUserID.String() {
			t.Fatalf("query = %+v, want limit+1 rows of the target", query)
		}
	}
}

func TestListUserPostsUsecase_Execute_ReadsAConstantNumberOfQueries(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 20)
	for index := range 30 {
		store.rows = append(store.rows, gridPostOf(input.TargetUserID.String(), index))
	}

	if _, err := usecase.NewListUserPosts(store, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	want := map[string]int{"users": 1, "blocks": 1, "page": 1, "collabs": 1}
	for name, count := range want {
		if store.calls[name] != count {
			t.Fatalf("calls = %v, want %v: one query per collection whatever the page size", store.calls, want)
		}
	}
	if len(store.collabLookup) != 20 {
		t.Fatalf("collab lookup has %d posts, want the 20 of the page in one $in", len(store.collabLookup))
	}
}

func TestListUserPostsUsecase_Execute_ResolvesCollabsAndLeavesOutPostsWithoutMedia(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 20)
	collabPost, noMedia, basic := gridPostOf(input.TargetUserID.String(), 0), gridPostOf(input.TargetUserID.String(), 1), gridPostOf(input.TargetUserID.String(), 2)
	noMedia.Media = nil
	store.rows = []usecase.FeedPost{collabPost, noMedia, basic}
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: collabPost.PostID, Status: pointer("OPEN")}}

	page, err := usecase.NewListUserPosts(store, discardLogger()).Execute(context.Background(), input)

	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if !slices.Equal(gridPostIDs(page), []string{collabPost.PostID, basic.PostID}) || page.NextCursor != nil {
		t.Fatalf("page = %v, next %v, want the post without media left out and no next page", gridPostIDs(page), page.NextCursor)
	}
	if page.Items[0].PostType != usecase.FeedPostTypeCollab || *page.Items[0].CollabStatus != "OPEN" || page.Items[1].CollabStatus != nil {
		t.Fatalf("items = %+v, want the collab resolved for the first only", page.Items)
	}
}

func TestUserGridUsecases_Execute_AnswerUserNotFoundForAUserTheCallerMayNotSee(t *testing.T) {
	cases := map[string]func(store *fakeGridStore, input usecase.UserGridInput){
		"unknown or deleted user": func(store *fakeGridStore, input usecase.UserGridInput) {
			delete(store.users, input.TargetUserID.String())
		},
		"block between caller and target": func(store *fakeGridStore, input usecase.UserGridInput) {
			store.blocked = []string{input.TargetUserID.String()}
		},
	}
	for name, hide := range cases {
		for grid, execute := range gridUsecases() {
			t.Run(name+" "+grid, func(t *testing.T) {
				store := newFakeGridStore()
				input := seedTarget(store, 20)
				store.rows = []usecase.FeedPost{gridPostOf(input.TargetUserID.String(), 0)}
				hide(store, input)

				_, err := execute(store, input)

				if !errors.Is(err, usecase.ErrGridUserNotFound) {
					t.Fatalf("Execute() error = %v, want ErrGridUserNotFound", err)
				}
				if store.calls["page"] != 0 {
					t.Fatal("the grid was read for a user the caller may not see")
				}
			})
		}
	}
}

// gridUsecases runs each grid usecase against a store.
func gridUsecases() map[string]func(*fakeGridStore, usecase.UserGridInput) (usecase.GridPage, error) {
	return map[string]func(*fakeGridStore, usecase.UserGridInput) (usecase.GridPage, error){
		"posts": func(store *fakeGridStore, input usecase.UserGridInput) (usecase.GridPage, error) {
			return usecase.NewListUserPosts(store, discardLogger()).Execute(context.Background(), input)
		},
		"reposts": func(store *fakeGridStore, input usecase.UserGridInput) (usecase.GridPage, error) {
			return usecase.NewListUserReposts(store, discardLogger()).Execute(context.Background(), input)
		},
	}
}

func TestUserGridUsecases_Execute_RejectInvalidInputBeforeReading(t *testing.T) {
	cases := map[string]func(*usecase.UserGridInput){
		"no caller":      func(input *usecase.UserGridInput) { input.UserID = uuid.Nil },
		"no target":      func(input *usecase.UserGridInput) { input.TargetUserID = uuid.Nil },
		"zero limit":     func(input *usecase.UserGridInput) { input.Limit = 0 },
		"limit above 50": func(input *usecase.UserGridInput) { input.Limit = 51 },
	}
	for name, breakInput := range cases {
		for grid, execute := range gridUsecases() {
			t.Run(name+" "+grid, func(t *testing.T) {
				store := newFakeGridStore()
				input := seedTarget(store, 20)
				breakInput(&input)

				if _, err := execute(store, input); err == nil || errors.Is(err, usecase.ErrGridUserNotFound) {
					t.Fatalf("Execute() error = %v, want a validation error", err)
				}
				if len(store.calls) != 0 {
					t.Fatalf("calls = %v, want none", store.calls)
				}
			})
		}
	}
}

func TestUserGridUsecases_Execute_PropagateAReadFailure(t *testing.T) {
	for grid, execute := range gridUsecases() {
		t.Run(grid, func(t *testing.T) {
			store := newFakeGridStore()
			input := seedTarget(store, 20)
			store.err = errors.New("mongo down")

			if _, err := execute(store, input); !errors.Is(err, store.err) {
				t.Fatalf("Execute() error = %v, want %v", err, store.err)
			}
		})
	}
}

// seedShares makes the target share posts by other creators, newest share first, and
// returns the shared posts in that order.
func seedShares(store *fakeGridStore, input usecase.UserGridInput, count int) []usecase.FeedPost {
	store.users["creator-1"] = usecase.FeedCreator{UserID: "creator-1", Username: "luis"}
	posts := make([]usecase.FeedPost, 0, count)
	for index := range count {
		post := gridPostOf("creator-1", index)
		store.posts[post.PostID] = post
		store.shares = append(store.shares, usecase.PostRef{PostID: post.PostID, At: gridEpoch.Add(-time.Duration(index) * time.Second)})
		posts = append(posts, post)
	}
	return posts
}

func TestListUserRepostsUsecase_Execute_ServesSharedPostsInShareOrderBatchingThePosts(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 5)
	posts := seedShares(store, input, 8)

	page, err := usecase.NewListUserReposts(store, discardLogger()).Execute(context.Background(), input)

	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	want := []string{posts[0].PostID, posts[1].PostID, posts[2].PostID, posts[3].PostID, posts[4].PostID}
	if !slices.Equal(gridPostIDs(page), want) || page.NextCursor == nil {
		t.Fatalf("page = %v (next %v), want the 5 most recent shares and a next page", gridPostIDs(page), page.NextCursor)
	}
	after, _ := usecase.DecodeGridCursor(*page.NextCursor)
	if after.PostID != posts[4].PostID || !after.CreatedAt.Equal(store.shares[4].At) {
		t.Fatalf("cursor = %+v, want the last share's (createdAt, postId)", after)
	}
	if len(store.postLookups) != 1 || len(store.postLookups[0]) != 5 {
		t.Fatalf("post lookups = %v, want one $in with the 5 shared posts", store.postLookups)
	}
	if !page.Items[0].CreatedAt.Equal(posts[0].CreatedAt) {
		t.Fatalf("createdAt = %v, want the post's", page.Items[0].CreatedAt)
	}
}

func TestListUserRepostsUsecase_Execute_SkipsSharesWhosePostIsGoneOrHiddenAndStillFillsThePage(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 3)
	posts := seedShares(store, input, 7)
	delete(store.posts, posts[0].PostID)
	delete(store.posts, posts[2].PostID)
	blocked, ghost := gridPostOf("blocker-1", 0), gridPostOf("deleted-user", 0)
	store.posts[blocked.PostID], store.posts[ghost.PostID] = blocked, ghost
	store.blocked = []string{"blocker-1"}
	store.shares[1].PostID, store.shares[3].PostID = blocked.PostID, ghost.PostID
	// Shares now: gone, blocked creator, gone, creator not projected, then posts 4, 5, 6.

	page, err := usecase.NewListUserReposts(store, discardLogger()).Execute(context.Background(), input)

	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if want := []string{posts[4].PostID, posts[5].PostID, posts[6].PostID}; !slices.Equal(gridPostIDs(page), want) {
		t.Fatalf("page = %v, want %v", gridPostIDs(page), want)
	}
	if page.NextCursor != nil {
		t.Fatalf("next = %v, want null: no share is left", *page.NextCursor)
	}
	// Read 1: shares 0-2, nothing visible. Read 2: shares 3-5, posts 4 and 5. Read 3:
	// one share missing, post 6, and nothing after it.
	if store.calls["page"] != 3 || store.calls["posts"] != 3 {
		t.Fatalf("calls = %v, want 3 share reads, each with one $in on posts (the page reads on until it is full)", store.calls)
	}
	if limits := []int{store.queries[0].Limit, store.queries[1].Limit, store.queries[2].Limit}; !slices.Equal(limits, []int{4, 4, 2}) {
		t.Fatalf("share read limits = %v, want the missing items + 1 each time", limits)
	}
}

func TestListUserRepostsUsecase_Execute_AnswersAShortPageAfterTheMaximumReads(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 2)
	posts := seedShares(store, input, 20)
	for _, post := range posts[:len(posts)-1] {
		delete(store.posts, post.PostID)
	}

	page, err := usecase.NewListUserReposts(store, discardLogger()).Execute(context.Background(), input)

	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if len(page.Items) != 0 || page.NextCursor == nil || store.calls["page"] != usecase.RefListMaxReads {
		t.Fatalf("items = %d, next %v, reads %d: want an empty page after %d reads with a cursor to go on", len(page.Items), page.NextCursor, store.calls["page"], usecase.RefListMaxReads)
	}
	after, _ := usecase.DecodeGridCursor(*page.NextCursor)
	if lastRead := store.shares[2*usecase.RefListMaxReads-1]; after.PostID != lastRead.PostID {
		t.Fatalf("cursor = %+v, want the last share read (%s), so the next page resumes after it", after, lastRead.PostID)
	}

	input.After = &after
	for page.NextCursor != nil {
		var err error
		if page, err = usecase.NewListUserReposts(store, discardLogger()).Execute(context.Background(), input); err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
		if page.NextCursor != nil {
			next, _ := usecase.DecodeGridCursor(*page.NextCursor)
			input.After = &next
		}
		if len(page.Items) == 1 && page.Items[0].PostID != posts[len(posts)-1].PostID {
			t.Fatalf("item = %s, want the one post left", page.Items[0].PostID)
		}
	}
}

func TestListUserRepostsUsecase_Execute_LogsTheSkippedSharesWithoutFailing(t *testing.T) {
	store := newFakeGridStore()
	input := seedTarget(store, 5)
	posts := seedShares(store, input, 2)
	delete(store.posts, posts[0].PostID)
	logger, logs := bufferLogger()

	if _, err := usecase.NewListUserReposts(store, logger).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if !strings.Contains(logs.String(), "reason=post_missing") || !strings.Contains(logs.String(), "correlation_id=corr-1") {
		t.Fatalf("logs = %s, want the skipped share's reason and correlation id", logs)
	}
}
