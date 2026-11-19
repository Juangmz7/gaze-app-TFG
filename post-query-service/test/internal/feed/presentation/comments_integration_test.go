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
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	commentusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	userusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// commentPageBody is the JSON of a comment list page, decoded field by field so a
// renamed field fails the test.
type commentPageBody struct {
	Items []struct {
		CommentID string `json:"commentId"`
		PostID    string `json:"postId"`
		Author    struct {
			UserID     string  `json:"userId"`
			Username   string  `json:"username"`
			PictureURL *string `json:"pictureUrl"`
		} `json:"author"`
		Content    string  `json:"content"`
		ReplyTo    *string `json:"replyTo"`
		LikeCount  int64   `json:"likeCount"`
		ReplyCount int64   `json:"replyCount"`
		LikedByMe  bool    `json:"likedByMe"`
		CreatedAt  string  `json:"createdAt"`
		UpdatedAt  string  `json:"updatedAt"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (p commentPageBody) commentIDs() []string {
	ids := make([]string, 0, len(p.Items))
	for _, item := range p.Items {
		ids = append(ids, item.CommentID)
	}
	return ids
}

// listComments GETs target and decodes a 200 page, failing on any other status.
func (a *feedApp) listComments(t *testing.T, target string) commentPageBody {
	t.Helper()

	recorder := serve(a.mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d, want 200: %s", target, recorder.Code, recorder.Body)
	}
	var page commentPageBody
	decoder := json.NewDecoder(recorder.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&page); err != nil {
		t.Fatalf("GET %s body is not a comment page: %v", target, err)
	}
	return page
}

// commentFixture is one comment to project, with the counters it ends up with.
type commentFixture struct {
	author    uuid.UUID
	replyTo   uuid.UUID
	likes     int64
	createdAt time.Time
}

// recordComment projects a comment through the comment context's real repository and
// moves its like counter the way the like handler does.
func (a *feedApp) recordComment(t *testing.T, ctx context.Context, postID uuid.UUID, fixture commentFixture) uuid.UUID {
	t.Helper()

	commentID := uuid.New()
	author := fixture.author
	if author == uuid.Nil {
		author = a.creator
	}
	if _, err := a.comments.Insert(ctx, commentusecase.RecordCommentInput{
		CommentID: commentID, PostID: postID, UserID: author, Content: "Comment " + commentID.String()[:8],
		ReplyTo: fixture.replyTo, CreatedAt: fixture.createdAt, UpdatedAt: fixture.createdAt,
	}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if fixture.replyTo != uuid.Nil {
		if _, err := a.comments.AdjustReplyCount(ctx, fixture.replyTo, 1); err != nil {
			t.Fatalf("AdjustReplyCount() error = %v", err)
		}
	}
	if fixture.likes > 0 {
		if _, err := a.comments.AdjustLikeCount(ctx, commentID, fixture.likes); err != nil {
			t.Fatalf("AdjustLikeCount() error = %v", err)
		}
	}
	return commentID
}

func (a *feedApp) registerUser(t *testing.T, ctx context.Context, username string) uuid.UUID {
	t.Helper()

	userID := uuid.New()
	if err := a.users.Insert(ctx, userusecase.RegisterUserInput{UserID: userID, Username: username, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	return userID
}

func commentsTarget(postID uuid.UUID, query string) string {
	return "/api/content/posts/" + postID.String() + "/comments" + query
}

func repliesTarget(postID, commentID uuid.UUID, query string) string {
	return "/api/content/posts/" + postID.String() + "/comments/" + commentID.String() + "/replies" + query
}

var commentsEpoch = time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

func TestListComments_OrdersByLikesThenRepliesThenNewest(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())

	// (likes, replies): (2,0), (5,1), (5,3), created in that order so creation order
	// never explains the result.
	twoLikes := app.recordComment(t, ctx, postID, commentFixture{likes: 2, createdAt: commentsEpoch})
	fiveLikesOneReply := app.recordComment(t, ctx, postID, commentFixture{likes: 5, createdAt: commentsEpoch.Add(time.Minute)})
	fiveLikesThreeReplies := app.recordComment(t, ctx, postID, commentFixture{likes: 5, createdAt: commentsEpoch.Add(2 * time.Minute)})
	app.recordComment(t, ctx, postID, commentFixture{replyTo: fiveLikesOneReply, createdAt: commentsEpoch.Add(3 * time.Minute)})
	for index := range 3 {
		app.recordComment(t, ctx, postID, commentFixture{replyTo: fiveLikesThreeReplies, createdAt: commentsEpoch.Add(time.Duration(4+index) * time.Minute)})
	}
	// Same likes and replies as twoLikes but newer: the newer one comes first.
	newerTwoLikes := app.recordComment(t, ctx, postID, commentFixture{likes: 2, createdAt: commentsEpoch.Add(10 * time.Minute)})

	page := app.listComments(t, commentsTarget(postID, ""))

	want := []string{fiveLikesThreeReplies.String(), fiveLikesOneReply.String(), newerTwoLikes.String(), twoLikes.String()}
	if got := page.commentIDs(); !slices.Equal(got, want) {
		t.Fatalf("order = %v, want %v ((5,3), (5,1), then (2,0) newest first); replies must not be listed", got, want)
	}
	counters := [][2]int64{}
	for _, item := range page.Items {
		counters = append(counters, [2]int64{item.LikeCount, item.ReplyCount})
	}
	if !slices.Equal(counters, [][2]int64{{5, 3}, {5, 1}, {2, 0}, {2, 0}}) {
		t.Fatalf("(likeCount, replyCount) = %v", counters)
	}
	if page.NextCursor != nil {
		t.Fatalf("nextCursor = %q, want null on the only page", *page.NextCursor)
	}
}

func TestListComments_ReturnsTheCommentShapeWithTheCallersLikes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	author := app.registerUser(t, ctx, "luis_mendez")
	liked := app.recordComment(t, ctx, postID, commentFixture{author: author, likes: 1, createdAt: commentsEpoch})
	notLiked := app.recordComment(t, ctx, postID, commentFixture{author: author, createdAt: commentsEpoch.Add(time.Minute)})
	likes := commentmongo.NewCommentLikeRepository(app.db)
	if _, err := likes.Insert(ctx, commentusecase.RecordCommentLikeInput{CommentID: liked, UserID: app.viewerID, CreatedAt: commentsEpoch}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	// Someone else's like is not the caller's.
	if _, err := likes.Insert(ctx, commentusecase.RecordCommentLikeInput{CommentID: notLiked, UserID: uuid.New(), CreatedAt: commentsEpoch}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	page := app.listComments(t, commentsTarget(postID, ""))

	if len(page.Items) != 2 {
		t.Fatalf("items = %d, want 2", len(page.Items))
	}
	first := page.Items[0]
	if first.CommentID != liked.String() || first.PostID != postID.String() || first.ReplyTo != nil || !first.LikedByMe || first.LikeCount != 1 {
		t.Fatalf("first item = %+v, want the liked top-level comment", first)
	}
	if first.Author.UserID != author.String() || first.Author.Username != "luis_mendez" || first.Author.PictureURL != nil {
		t.Fatalf("author = %+v, want the UserSummary of the author", first.Author)
	}
	if first.CreatedAt != "2026-03-01T10:00:00Z" || first.UpdatedAt != "2026-03-01T10:00:00Z" || first.Content == "" {
		t.Fatalf("first item = %+v", first)
	}
	if page.Items[1].LikedByMe {
		t.Fatal("likedByMe = true for a comment only someone else liked")
	}
}

func TestListComments_PagesThroughThreePagesWithoutDuplicates(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())

	// Ties on likes and on the creation date, so the cursor must use every key.
	var want []string
	fixtures := []commentFixture{}
	for index := range 7 {
		fixtures = append(fixtures, commentFixture{likes: int64(index % 2), createdAt: commentsEpoch.Add(time.Duration(index/3) * time.Minute)})
	}
	ids := make([]uuid.UUID, len(fixtures))
	for index, fixture := range fixtures {
		ids[index] = app.recordComment(t, ctx, postID, fixture)
	}
	order := make([]int, len(fixtures))
	for index := range order {
		order[index] = index
	}
	slices.SortFunc(order, func(a, b int) int {
		if fixtures[a].likes != fixtures[b].likes {
			return int(fixtures[b].likes - fixtures[a].likes)
		}
		if !fixtures[a].createdAt.Equal(fixtures[b].createdAt) {
			return fixtures[b].createdAt.Compare(fixtures[a].createdAt)
		}
		return -strings.Compare(ids[a].String(), ids[b].String())
	})
	for _, index := range order {
		want = append(want, ids[index].String())
	}

	var got []string
	var sizes []int
	query := "?limit=3"
	for range 4 {
		page := app.listComments(t, commentsTarget(postID, query))
		got = append(got, page.commentIDs()...)
		sizes = append(sizes, len(page.Items))
		if page.NextCursor == nil {
			break
		}
		query = "?limit=3&cursor=" + url.QueryEscape(*page.NextCursor)
	}

	if !slices.Equal(sizes, []int{3, 3, 1}) {
		t.Fatalf("page sizes = %v, want [3 3 1] ending with a null nextCursor", sizes)
	}
	if !slices.Equal(got, want) {
		t.Fatalf("comments across pages = %v, want %v (no duplicates, nothing skipped)", got, want)
	}
}

func TestListReplies_ReturnsRepliesOldestFirst(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	parent := app.recordComment(t, ctx, postID, commentFixture{createdAt: commentsEpoch})
	other := app.recordComment(t, ctx, postID, commentFixture{createdAt: commentsEpoch})

	// Projected out of order; one reply has many likes, which the replies order ignores.
	third := app.recordComment(t, ctx, postID, commentFixture{replyTo: parent, createdAt: commentsEpoch.Add(3 * time.Minute)})
	first := app.recordComment(t, ctx, postID, commentFixture{replyTo: parent, likes: 9, createdAt: commentsEpoch.Add(time.Minute)})
	second := app.recordComment(t, ctx, postID, commentFixture{replyTo: parent, createdAt: commentsEpoch.Add(2 * time.Minute)})
	app.recordComment(t, ctx, postID, commentFixture{replyTo: other, createdAt: commentsEpoch.Add(time.Minute)})

	firstPage := app.listComments(t, repliesTarget(postID, parent, "?limit=2"))
	if firstPage.NextCursor == nil {
		t.Fatal("nextCursor = null, want a second page")
	}
	secondPage := app.listComments(t, repliesTarget(postID, parent, "?limit=2&cursor="+url.QueryEscape(*firstPage.NextCursor)))

	got := append(firstPage.commentIDs(), secondPage.commentIDs()...)
	if want := []string{first.String(), second.String(), third.String()}; !slices.Equal(got, want) {
		t.Fatalf("replies = %v, want %v (oldest first, only this parent's)", got, want)
	}
	if secondPage.NextCursor != nil {
		t.Fatalf("nextCursor = %q, want null on the last page", *secondPage.NextCursor)
	}
	if reply := firstPage.Items[0]; reply.ReplyTo == nil || *reply.ReplyTo != parent.String() || reply.LikeCount != 9 {
		t.Fatalf("first reply = %+v, want replyTo the parent", reply)
	}
}

func TestListComments_LeavesOutCommentsAcrossABlock(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	blocks := blockmongo.NewRepository(app.db)
	blockedTheCaller := app.registerUser(t, ctx, "blocked_the_caller")
	blockedByTheCaller := app.registerUser(t, ctx, "blocked_by_the_caller")
	friend := app.registerUser(t, ctx, "friend")
	for _, block := range []blockusecase.CreateBlockInput{
		{BlockerUserID: blockedTheCaller, BlockedUserID: app.viewerID, CreatedAt: commentsEpoch},
		{BlockerUserID: app.viewerID, BlockedUserID: blockedByTheCaller, CreatedAt: commentsEpoch},
	} {
		if err := blocks.Insert(ctx, block); err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
	}
	// The hidden comments are the most liked, so a filter applied after the query would
	// leave a short first page.
	app.recordComment(t, ctx, postID, commentFixture{author: blockedTheCaller, likes: 9, createdAt: commentsEpoch})
	app.recordComment(t, ctx, postID, commentFixture{author: blockedByTheCaller, likes: 8, createdAt: commentsEpoch})
	visible := app.recordComment(t, ctx, postID, commentFixture{author: friend, likes: 1, createdAt: commentsEpoch})
	app.recordComment(t, ctx, postID, commentFixture{author: friend, createdAt: commentsEpoch})
	hiddenParentReply := app.recordComment(t, ctx, postID, commentFixture{author: blockedTheCaller, replyTo: visible, createdAt: commentsEpoch.Add(time.Minute)})
	visibleReply := app.recordComment(t, ctx, postID, commentFixture{author: friend, replyTo: visible, createdAt: commentsEpoch.Add(2 * time.Minute)})

	page := app.listComments(t, commentsTarget(postID, "?limit=1"))
	if got := page.commentIDs(); !slices.Equal(got, []string{visible.String()}) || page.NextCursor == nil {
		t.Fatalf("first page = %v (next %v), want only the visible comment and a next page", got, page.NextCursor)
	}
	replies := app.listComments(t, repliesTarget(postID, visible, ""))
	if got := replies.commentIDs(); !slices.Equal(got, []string{visibleReply.String()}) {
		t.Fatalf("replies = %v, want only %s (not %s)", got, visibleReply, hiddenParentReply)
	}

	// The replies of a comment written across a block are not reachable either.
	hiddenParent := app.recordComment(t, ctx, postID, commentFixture{author: blockedByTheCaller, createdAt: commentsEpoch})
	recorder := serve(app.mux, http.MethodGet, repliesTarget(postID, hiddenParent, ""), "", nil)
	assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "comment_not_found")
}

// assertErrorBody checks the status and the shared error body's code.
func assertErrorBody(t *testing.T, status int, body []byte, wantStatus int, wantCode string) {
	t.Helper()

	var decoded common.ApiErrorResponse
	if err := json.Unmarshal(body, &decoded); err != nil {
		t.Fatalf("body is not an error response (status %d): %s", status, body)
	}
	if status != wantStatus || decoded.Code != wantCode {
		t.Fatalf("status = %d, code = %q, want %d %q: %s", status, decoded.Code, wantStatus, wantCode, body)
	}
}

func TestListComments_AnswersTheSame404ForAPostTheCallerMayNotSee(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	blocked := app.insertPost(t, ctx, sampleMedia())
	blockedComment := app.recordComment(t, ctx, blocked, commentFixture{createdAt: commentsEpoch})
	if err := blockmongo.NewRepository(app.db).Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.creator, BlockedUserID: app.viewerID, CreatedAt: commentsEpoch}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	missing := uuid.New()

	for name, target := range map[string]string{
		"comments of a post never projected": commentsTarget(missing, ""),
		"replies of a post never projected":  repliesTarget(missing, uuid.New(), ""),
		"comments of a creator who blocked":  commentsTarget(blocked, ""),
		"replies on a creator who blocked":   repliesTarget(blocked, blockedComment, ""),
	} {
		t.Run(name, func(t *testing.T) {
			recorder := serve(app.mux, http.MethodGet, target, "", nil)
			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "post_not_found")
		})
	}
}

func TestListReplies_Answers404CommentNotFoundForAParentThatIsNotOfThePost(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	otherPost := app.insertPost(t, ctx, sampleMedia())
	ofOtherPost := app.recordComment(t, ctx, otherPost, commentFixture{createdAt: commentsEpoch})

	for name, commentID := range map[string]uuid.UUID{
		"never projected": uuid.New(),
		"of another post": ofOtherPost,
	} {
		t.Run(name, func(t *testing.T) {
			recorder := serve(app.mux, http.MethodGet, repliesTarget(postID, commentID, ""), "", nil)
			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "comment_not_found")
		})
	}
}

func TestListComments_RejectsAMalformedCursorOrLimitWith400(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	parent := app.recordComment(t, ctx, postID, commentFixture{createdAt: commentsEpoch})
	repliesCursor := usecase.EncodeRepliesCursor(usecase.RepliesCursor{CreatedAt: commentsEpoch, CommentID: uuid.NewString()})

	cases := map[string]struct {
		target string
		code   string
	}{
		"garbage cursor":               {commentsTarget(postID, "?cursor=not-a-cursor"), "invalid_cursor"},
		"a replies cursor on comments": {commentsTarget(postID, "?cursor="+repliesCursor), "invalid_cursor"},
		"limit above the maximum":      {commentsTarget(postID, "?limit=51"), "invalid_limit"},
		"limit on the replies":         {repliesTarget(postID, parent, "?limit=0"), "invalid_limit"},
		"a malformed comment id":       {"/api/content/posts/" + postID.String() + "/comments/not-a-uuid/replies", "invalid_comment_id"},
		"a malformed post id":          {"/api/content/posts/not-a-uuid/comments", "invalid_post_id"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			recorder := serve(app.mux, http.MethodGet, tc.target, "", nil)
			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, tc.code)
		})
	}
}

// TestListComments_EveryPageIsAnIndexSeek reads the queries the service really ran from
// the MongoDB profiler and explains each one: one IXSCAN of the compound index, no SORT
// stage, and page 50 of 1100 comments examines as many keys as page 2, which a skip
// could never do (a skip to page 50 walks 980 keys first). Nine comments in ten have no
// like, so a scan that started at the first comment with the cursor's like count would
// walk most of the post.
func TestListComments_EveryPageIsAnIndexSeek(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	const total, limit = 1100, 20
	documents := make([]any, 0, total)
	for index := range total {
		likes := int64(0)
		if index%10 == 0 {
			likes = int64(1 + index%7)
		}
		createdAt := commentsEpoch.Add(time.Duration(index%13) * time.Second)
		documents = append(documents, bson.D{
			{Key: "comment_id", Value: uuid.NewString()}, {Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: app.creator.String()},
			{Key: "content", Value: fmt.Sprintf("Comment %d", index)}, {Key: "reply_to", Value: ""},
			{Key: "like_count", Value: likes}, {Key: "reply_count", Value: int64(index % 3)},
			{Key: "created_at", Value: createdAt}, {Key: "updated_at", Value: createdAt}, {Key: "version", Value: int64(1)},
		})
	}
	if _, err := app.db.Collection("post_comments").InsertMany(ctx, documents); err != nil {
		t.Fatalf("InsertMany() error = %v", err)
	}

	pageQuery := func(cursor *string) string {
		if cursor == nil {
			return fmt.Sprintf("?limit=%d", limit)
		}
		return fmt.Sprintf("?limit=%d&cursor=%s", limit, url.QueryEscape(*cursor))
	}
	// Every page is one request with the previous nextCursor; pages 1, 2 and 50 are
	// measured.
	measured := map[int][]profiledQuery{}
	seen := map[string]bool{}
	var cursor *string
	for number := 1; number <= 50; number++ {
		profile := number == 1 || number == 2 || number == 50
		if profile {
			restartProfiler(t, ctx, app.db)
		}
		page := app.listComments(t, commentsTarget(postID, pageQuery(cursor)))
		if profile {
			measured[number] = commentPageQueries(t, ctx, app.db)
		}
		if len(page.Items) != limit || page.NextCursor == nil {
			t.Fatalf("page %d has %d items (next %v), want a full page with a next one", number, len(page.Items), page.NextCursor)
		}
		for _, id := range page.commentIDs() {
			if seen[id] {
				t.Fatalf("comment %s served twice, on page %d", id, number)
			}
			seen[id] = true
		}
		cursor = page.NextCursor
	}

	keys := map[int]int64{}
	for number, queries := range measured {
		keys[number] = assertIndexSeeks(t, ctx, app.db, fmt.Sprintf("page %d", number), queries, commentmongo.TopLevelCommentsIndex, keysetBranches, limit)
	}
	if keys[50] > keys[2]+keysetBranches {
		t.Fatalf("page 50 examined %d keys, page 2 %d: a deep page must cost what an early one costs", keys[50], keys[2])
	}
}

func TestListReplies_IsAnIndexSeekWithNoSort(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	parent := app.recordComment(t, ctx, postID, commentFixture{createdAt: commentsEpoch})
	for index := range 30 {
		app.recordComment(t, ctx, postID, commentFixture{replyTo: parent, createdAt: commentsEpoch.Add(time.Duration(index/4) * time.Second)})
	}

	page := app.listComments(t, repliesTarget(postID, parent, "?limit=5"))
	for range 3 {
		page = app.listComments(t, repliesTarget(postID, parent, "?limit=5&cursor="+url.QueryEscape(*page.NextCursor)))
	}
	restartProfiler(t, ctx, app.db)
	app.listComments(t, repliesTarget(postID, parent, "?limit=5&cursor="+url.QueryEscape(*page.NextCursor)))

	assertIndexSeeks(t, ctx, app.db, "replies page 5", commentPageQueries(t, ctx, app.db), commentmongo.RepliesIndex, repliesKeysetBranches, 5)
}

// Keyset ranges per page: (L, R, C, <I), (L, R, <C), (L, <R), (<L) for the top-level
// list, and (C, >I), (>C) for the replies.
const (
	keysetBranches        = 4
	repliesKeysetBranches = 2
)

// assertIndexSeeks checks every page query of one request: no in-memory sort, one
// IXSCAN of index in the explained plan, and at most limit+1 rows read plus one
// boundary key per range in all. It returns the keys examined.
func assertIndexSeeks(t *testing.T, ctx context.Context, db *mongodriver.Database, name string, queries []profiledQuery, index string, ranges, limit int) int64 {
	t.Helper()

	if len(queries) == 0 || len(queries) > ranges {
		t.Fatalf("%s ran %d page queries, want 1 to %d", name, len(queries), ranges)
	}
	var keys, documents int64
	for _, query := range queries {
		keys += query.KeysExamined
		documents += query.DocsExamined
		if query.HasSortStage {
			t.Fatalf("%s sorted in memory: %s", name, query.PlanSummary)
		}
		stages, indexes := explainedPlan(t, ctx, db, query.Command)
		if slices.Contains(stages, "SORT") || slices.Contains(stages, "SORT_MERGE") || len(indexes) != 1 || indexes[0] != index {
			t.Fatalf("%s plan stages = %v on %v, want one IXSCAN of %s and no SORT", name, stages, indexes, index)
		}
	}
	t.Logf("%s: %d queries, %d keys and %d documents examined", name, len(queries), keys, documents)
	if bound := int64(limit + 1 + ranges); keys > bound || documents > bound {
		t.Fatalf("%s examined %d keys and %d documents, want at most %d", name, keys, documents, bound)
	}

	return keys
}

// profiledQuery is what the profiler recorded about one find.
type profiledQuery struct {
	Command      bson.Raw `bson:"command"`
	PlanSummary  string   `bson:"planSummary"`
	KeysExamined int64    `bson:"keysExamined"`
	DocsExamined int64    `bson:"docsExamined"`
	HasSortStage bool     `bson:"hasSortStage"`
}

// restartProfiler empties the profiler and records every operation from now on.
func restartProfiler(t *testing.T, ctx context.Context, db *mongodriver.Database) {
	t.Helper()

	if err := db.RunCommand(ctx, bson.D{{Key: "profile", Value: 0}}).Err(); err != nil {
		t.Fatalf("profile 0 error = %v", err)
	}
	if err := db.Collection("system.profile").Drop(ctx); err != nil {
		t.Fatalf("drop system.profile error = %v", err)
	}
	if err := db.RunCommand(ctx, bson.D{{Key: "profile", Value: 2}}).Err(); err != nil {
		t.Fatalf("profile 2 error = %v", err)
	}
}

// commentPageQueries are the page queries on post_comments since restartProfiler: the
// finds with a sort, which the parent lookup (FindComment) never has.
func commentPageQueries(t *testing.T, ctx context.Context, db *mongodriver.Database) []profiledQuery {
	t.Helper()
	return pageQueries(t, ctx, db, "post_comments")
}

// pageQueries reads the sorted finds the profiler recorded on collection: the page
// queries of a keyset list.
func pageQueries(t *testing.T, ctx context.Context, db *mongodriver.Database, collection string) []profiledQuery {
	t.Helper()

	filter := bson.D{
		{Key: "ns", Value: db.Name() + "." + collection},
		{Key: "op", Value: "query"},
		{Key: "command.sort", Value: bson.D{{Key: "$exists", Value: true}}},
	}
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

// explainedPlan explains the profiled find exactly as it ran and returns every stage
// of the winning plan and the indexes it scans.
func explainedPlan(t *testing.T, ctx context.Context, db *mongodriver.Database, command bson.Raw) ([]string, []string) {
	t.Helper()

	find := bson.D{}
	for _, field := range []string{"find", "filter", "sort", "projection", "hint", "limit"} {
		if value, err := command.LookupErr(field); err == nil {
			find = append(find, bson.E{Key: field, Value: value})
		}
	}
	var explained bson.Raw
	if err := db.RunCommand(ctx, bson.D{{Key: "explain", Value: find}, {Key: "verbosity", Value: "queryPlanner"}}).Decode(&explained); err != nil {
		t.Fatalf("explain error = %v", err)
	}
	winning, err := explained.LookupErr("queryPlanner", "winningPlan")
	if err != nil {
		t.Fatalf("explain has no winning plan: %s", explained)
	}

	var stages, indexes []string
	collectPlan(winning, &stages, &indexes)
	return stages, indexes
}

// collectPlan walks a plan tree, whatever its nesting (inputStage, inputStages,
// queryPlan in the slot-based engine).
func collectPlan(value bson.RawValue, stages, indexes *[]string) {
	switch value.Type {
	case bson.TypeEmbeddedDocument:
		elements, _ := value.Document().Elements()
		for _, element := range elements {
			switch element.Key() {
			case "stage":
				*stages = append(*stages, element.Value().StringValue())
			case "indexName":
				*indexes = append(*indexes, element.Value().StringValue())
			case "slotBasedPlan":
				continue
			default:
				collectPlan(element.Value(), stages, indexes)
			}
		}
	case bson.TypeArray:
		values, _ := value.Array().Values()
		for _, element := range values {
			collectPlan(element, stages, indexes)
		}
	}
}
