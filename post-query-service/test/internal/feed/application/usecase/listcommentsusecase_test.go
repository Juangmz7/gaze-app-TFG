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

// fakeCommentStore is the read models of the comment lists. Its page queries return the
// rows they were given, in order: sorting and the keyset are MongoDB's job and are
// tested against it (test/internal/feed/presentation/comments_integration_test.go).
type fakeCommentStore struct {
	posts    map[string]usecase.FeedPost
	users    map[string]usecase.FeedCreator
	blocked  []string
	liked    []string
	comments map[string]usecase.ContentComment
	rows     []usecase.ContentComment
	err      error

	calls          map[string]int
	topLevelQuery  usecase.TopLevelCommentsQuery
	repliesQuery   usecase.RepliesQuery
	likedLookup    []string
	creatorsLookup []string
}

func newFakeCommentStore() *fakeCommentStore {
	return &fakeCommentStore{
		posts:    map[string]usecase.FeedPost{},
		users:    map[string]usecase.FeedCreator{},
		comments: map[string]usecase.ContentComment{},
		calls:    map[string]int{},
	}
}

func (f *fakeCommentStore) FindPosts(_ context.Context, postIDs []string) ([]usecase.FeedPost, error) {
	f.calls["posts"]++
	var found []usecase.FeedPost
	for _, id := range postIDs {
		if post, ok := f.posts[id]; ok {
			found = append(found, post)
		}
	}
	return found, f.err
}

func (f *fakeCommentStore) FindCreators(_ context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	f.calls["users"]++
	f.creatorsLookup = userIDs
	var found []usecase.FeedCreator
	for _, id := range userIDs {
		if user, ok := f.users[id]; ok {
			found = append(found, user)
		}
	}
	return found, nil
}

func (f *fakeCommentStore) FindAllBlockRelatedUserIDs(context.Context, string) ([]string, error) {
	f.calls["blocks"]++
	return f.blocked, nil
}

func (f *fakeCommentStore) FindLikedCommentIDs(_ context.Context, _ string, commentIDs []string) ([]string, error) {
	f.calls["likes"]++
	f.likedLookup = commentIDs
	return f.liked, nil
}

func (f *fakeCommentStore) FindTopLevelComments(_ context.Context, query usecase.TopLevelCommentsQuery) ([]usecase.ContentComment, error) {
	f.calls["comments"]++
	f.topLevelQuery = query
	return limitRows(f.rows, query.Limit), nil
}

func (f *fakeCommentStore) FindComment(_ context.Context, commentID string) (usecase.ContentComment, bool, error) {
	f.calls["comment"]++
	comment, found := f.comments[commentID]
	return comment, found, nil
}

func (f *fakeCommentStore) FindReplies(_ context.Context, query usecase.RepliesQuery) ([]usecase.ContentComment, error) {
	f.calls["comments"]++
	f.repliesQuery = query
	return limitRows(f.rows, query.Limit), nil
}

func limitRows(rows []usecase.ContentComment, limit int) []usecase.ContentComment {
	if len(rows) > limit {
		return rows[:limit]
	}
	return rows
}

// seedCommentedPost stores a post by creator-1 and its creator, and returns the post id.
func seedCommentedPost(store *fakeCommentStore) uuid.UUID {
	postID := uuid.New()
	store.posts[postID.String()] = usecase.FeedPost{PostID: postID.String(), CreatorID: "creator-1"}
	store.users["creator-1"] = usecase.FeedCreator{UserID: "creator-1", Username: "ana"}
	return postID
}

// seedRows stores count top-level comments of postID by author-1, in list order.
func seedRows(store *fakeCommentStore, postID uuid.UUID, count int) {
	store.users["author-1"] = usecase.FeedCreator{UserID: "author-1", Username: "luis"}
	for index := range count {
		store.rows = append(store.rows, usecase.ContentComment{
			CommentID: uuid.NewString(), PostID: postID.String(), AuthorID: "author-1", Content: "Nice",
			LikeCount: int64(count - index), ReplyCount: 1, CreatedAt: time.Date(2026, 3, 1, 10, 0, index, 0, time.UTC),
		})
	}
}

func listComments(t *testing.T, store *fakeCommentStore, input usecase.ListCommentsInput) usecase.CommentPage {
	t.Helper()

	page, err := usecase.NewListComments(store, discardLogger()).Execute(context.Background(), input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	return page
}

func TestListCommentsUsecase_Execute_ReadsOneMoreRowThanTheLimitAndResumesAfterTheFullSortKeyOfTheLastComment(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	seedRows(store, postID, 5)
	after := &usecase.CommentsCursor{LikeCount: 9, ReplyCount: 2, CreatedAt: time.Date(2026, 3, 1, 9, 0, 0, 0, time.UTC), CommentID: uuid.NewString()}

	page := listComments(t, store, usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, After: after, Limit: 3})

	if store.topLevelQuery.Limit != 4 || store.topLevelQuery.After != after || store.topLevelQuery.PostID != postID.String() {
		t.Fatalf("query = %+v, want limit+1 rows after the given cursor", store.topLevelQuery)
	}
	if len(page.Items) != 3 || page.Items[2].Comment.CommentID != store.rows[2].CommentID {
		t.Fatalf("items = %d, want the first 3 rows", len(page.Items))
	}
	if page.NextCursor == nil {
		t.Fatal("nextCursor = null, want a next page: a fourth row was read")
	}
	next, err := usecase.DecodeCommentsCursor(*page.NextCursor)
	if err != nil {
		t.Fatalf("DecodeCommentsCursor() error = %v", err)
	}
	if next != usecase.CommentsCursorOf(store.rows[2]) {
		t.Fatalf("next cursor = %+v, want the full sort key of the last comment served %+v", next, usecase.CommentsCursorOf(store.rows[2]))
	}
}

func TestListCommentsUsecase_Execute_EndsWithANullCursorWhenNoExtraRowWasRead(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	seedRows(store, postID, 3)

	page := listComments(t, store, usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 3})

	if len(page.Items) != 3 || page.NextCursor != nil {
		t.Fatalf("items = %d, nextCursor = %v, want 3 and null", len(page.Items), page.NextCursor)
	}
}

func TestListCommentsUsecase_Execute_ExcludesTheCommentsOfEveryUserWithABlockInTheQuery(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	store.blocked = []string{"blocker-1", "blocked-1"}

	listComments(t, store, usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 20})

	got := slices.Sorted(slices.Values(store.topLevelQuery.ExcludedAuthorIDs))
	if !slices.Equal(got, []string{"blocked-1", "blocker-1"}) {
		t.Fatalf("excluded authors = %v, want both directions of the caller's blocks", got)
	}
}

func TestListCommentsUsecase_Execute_HydratesAuthorsAndLikesWithOneQueryEach(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	seedRows(store, postID, 4)
	store.liked = []string{store.rows[1].CommentID}

	page := listComments(t, store, usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 20})

	for _, collection := range []string{"posts", "blocks", "comments", "users", "likes"} {
		if store.calls[collection] != 1 {
			t.Fatalf("calls = %v, want one query per collection whatever the page size", store.calls)
		}
	}
	if len(store.calls) != 5 {
		t.Fatalf("calls = %v, want exactly 5 queries", store.calls)
	}
	if len(store.likedLookup) != 4 || !slices.Equal(store.creatorsLookup, []string{"creator-1", "author-1"}) {
		t.Fatalf("liked lookup = %v, users lookup = %v, want the page's ids and each user once", store.likedLookup, store.creatorsLookup)
	}
	liked := []bool{}
	for _, item := range page.Items {
		liked = append(liked, item.LikedByMe)
		if item.Author.Username != "luis" {
			t.Fatalf("author = %+v", item.Author)
		}
	}
	if !slices.Equal(liked, []bool{false, true, false, false}) {
		t.Fatalf("likedByMe = %v, want only the liked comment", liked)
	}
}

func TestListCommentsUsecase_Execute_LeavesOutACommentWhoseAuthorIsNoLongerProjected(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	seedRows(store, postID, 3)
	store.rows[1].AuthorID = "deleted-user"
	logger, logs := bufferLogger()

	page, err := usecase.NewListComments(store, logger).Execute(context.Background(), usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 2, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(page.Items) != 1 || page.Items[0].Comment.CommentID != store.rows[0].CommentID {
		t.Fatalf("items = %+v, want only the comment with a projected author", page.Items)
	}
	// The cursor still comes from the last row read, so the next page starts after it.
	next, err := usecase.DecodeCommentsCursor(*page.NextCursor)
	if err != nil || next.CommentID != store.rows[1].CommentID {
		t.Fatalf("next cursor = %+v, %v, want the last row read", next, err)
	}
	if !strings.Contains(logs.String(), "comments left out of a page") || !strings.Contains(logs.String(), "correlation_id=corr-1") {
		t.Fatalf("log = %s", logs)
	}
}

func TestListCommentsUsecase_Execute_ReturnsNotFoundWhenTheCallerMayNotSeeThePost(t *testing.T) {
	cases := map[string]func(store *fakeCommentStore, postID uuid.UUID){
		"post missing":    func(store *fakeCommentStore, postID uuid.UUID) { delete(store.posts, postID.String()) },
		"creator missing": func(store *fakeCommentStore, _ uuid.UUID) { delete(store.users, "creator-1") },
		"blocked creator": func(store *fakeCommentStore, _ uuid.UUID) { store.blocked = []string{"creator-1"} },
	}

	for name, hide := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeCommentStore()
			postID := seedCommentedPost(store)
			hide(store, postID)

			_, err := usecase.NewListComments(store, discardLogger()).Execute(context.Background(), usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 20})

			if !errors.Is(err, usecase.ErrCommentsPostNotFound) {
				t.Fatalf("Execute() error = %v, want ErrCommentsPostNotFound", err)
			}
		})
	}
}

func TestListCommentsUsecase_Execute_RejectsAnInvalidInput(t *testing.T) {
	postID := uuid.New()
	cases := map[string]usecase.ListCommentsInput{
		"no user":       {PostID: postID, Limit: 20},
		"no post":       {UserID: uuid.New(), Limit: 20},
		"zero limit":    {UserID: uuid.New(), PostID: postID},
		"limit over 50": {UserID: uuid.New(), PostID: postID, Limit: 51},
	}

	for name, input := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeCommentStore()
			if _, err := usecase.NewListComments(store, discardLogger()).Execute(context.Background(), input); err == nil || len(store.calls) != 0 {
				t.Fatalf("Execute() error = %v, calls = %v, want an error before any query", err, store.calls)
			}
		})
	}
}

func TestListCommentsUsecase_Execute_PropagatesARepositoryFailure(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	store.err = errors.New("mongo down")

	_, err := usecase.NewListComments(store, discardLogger()).Execute(context.Background(), usecase.ListCommentsInput{UserID: uuid.New(), PostID: postID, Limit: 20})

	if !errors.Is(err, store.err) || errors.Is(err, usecase.ErrCommentsPostNotFound) {
		t.Fatalf("Execute() error = %v, want the repository failure", err)
	}
}
