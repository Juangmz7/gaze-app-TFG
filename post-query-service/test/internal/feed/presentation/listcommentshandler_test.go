package presentation_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
)

func sampleCommentPageOutput(postID uuid.UUID) usecase.CommentPage {
	picture := "https://media.test/ana.jpg"
	next := "next-page"
	createdAt := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	return usecase.CommentPage{
		Items: []usecase.CommentItem{{
			Comment:   usecase.ContentComment{CommentID: uuid.NewString(), PostID: postID.String(), AuthorID: "author-1", Content: "Lovely light", LikeCount: 2, CreatedAt: createdAt, UpdatedAt: createdAt},
			Author:    usecase.FeedCreator{UserID: "author-1", Username: "ana", PictureURL: &picture},
			LikedByMe: true,
		}},
		NextCursor: &next,
	}
}

func newCommentsMux(t *testing.T, subject string, usecases *fakeUsecases, signer usecase.MediaURLSigner) *http.ServeMux {
	t.Helper()

	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, subject), presentation.Routes{
		ListComments: presentation.NewListCommentsHandler(fakeListComments{usecases}, signer, discardLogger()),
		ListReplies:  presentation.NewListRepliesHandler(fakeListReplies{usecases}, signer, discardLogger()),
	})
	return mux
}

func TestListCommentsHandler_PassesTheCallerPathAndDefaultLimitAndAnswersThePage(t *testing.T) {
	userID, postID := uuid.New(), uuid.New()
	usecases := &fakeUsecases{comments: sampleCommentPageOutput(postID)}
	mux := newCommentsMux(t, userID.String(), usecases, unsignedURLs{})

	recorder := serve(mux, http.MethodGet, "/api/content/posts/"+postID.String()+"/comments", "", map[string]string{presentation.CorrelationIDHeader: "corr-1"})

	if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("status = %d, content type %q: %s", recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body)
	}
	input := usecases.commentList[0]
	if input.UserID != userID || input.PostID != postID || input.Limit != usecase.KeysetDefaultLimit || input.After != nil || input.CorrelationID != "corr-1" {
		t.Fatalf("input = %+v, want the caller, the path post, limit 20 and no cursor", input)
	}
	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	items := body["items"].([]any)
	if len(items) != 1 || body["nextCursor"] != "next-page" || items[0].(map[string]any)["likedByMe"] != true {
		t.Fatalf("body = %v", body)
	}
}

func TestListCommentsHandler_DecodesTheCursorAndLimit(t *testing.T) {
	postID := uuid.New()
	usecases := &fakeUsecases{}
	mux := newCommentsMux(t, uuid.NewString(), usecases, unsignedURLs{})
	commentsCursor := usecase.CommentsCursor{LikeCount: 5, ReplyCount: 1, CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), CommentID: uuid.NewString()}
	repliesCursor := usecase.RepliesCursor{CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), CommentID: uuid.NewString()}
	parentID := uuid.New()

	serve(mux, http.MethodGet, "/api/content/posts/"+postID.String()+"/comments?limit=50&cursor="+url.QueryEscape(usecase.EncodeCommentsCursor(commentsCursor)), "", nil)
	serve(mux, http.MethodGet, "/api/content/posts/"+postID.String()+"/comments/"+parentID.String()+"/replies?limit=1&cursor="+url.QueryEscape(usecase.EncodeRepliesCursor(repliesCursor)), "", nil)

	if got := usecases.commentList; len(got) != 1 || got[0].Limit != 50 || got[0].After == nil || *got[0].After != commentsCursor {
		t.Fatalf("comments input = %+v", got)
	}
	if got := usecases.replyList; len(got) != 1 || got[0].Limit != 1 || got[0].CommentID != parentID || got[0].After == nil || *got[0].After != repliesCursor {
		t.Fatalf("replies input = %+v", got)
	}
}

func TestListCommentsHandler_RejectsMalformedParametersWith400WithoutCallingTheUsecase(t *testing.T) {
	postID, parentID := uuid.NewString(), uuid.NewString()
	comments := "/api/content/posts/" + postID + "/comments"
	replies := comments + "/" + parentID + "/replies"
	cases := []struct {
		target string
		code   string
		field  string
	}{
		{"/api/content/posts/not-a-uuid/comments", "invalid_post_id", "postId"},
		{"/api/content/posts/" + strings.ToUpper(strings.ReplaceAll(postID, "-", "")) + "/comments", "invalid_post_id", "postId"},
		{"/api/content/posts/" + uuid.Nil.String() + "/comments", "invalid_post_id", "postId"},
		{"/api/content/posts/{" + postID + "}/comments/" + parentID + "/replies", "invalid_post_id", "postId"},
		{comments + "/urn:uuid:" + parentID + "/replies", "invalid_comment_id", "commentId"},
		{comments + "/" + uuid.Nil.String() + "/replies", "invalid_comment_id", "commentId"},
		{comments + "?cursor=", "invalid_cursor", "cursor"},
		{comments + "?cursor=garbage", "invalid_cursor", "cursor"},
		{replies + "?cursor=" + usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: uuid.NewString()}), "invalid_cursor", "cursor"},
	}
	for _, limit := range []string{"", "0", "51", "-1", "%2B5", "1.5", "abc", "007", "100", "%205"} {
		cases = append(cases, struct {
			target string
			code   string
			field  string
		}{comments + "?limit=" + limit, "invalid_limit", "limit"})
	}

	for _, tc := range cases {
		t.Run(tc.target, func(t *testing.T) {
			usecases := &fakeUsecases{}
			mux := newCommentsMux(t, uuid.NewString(), usecases, unsignedURLs{})

			recorder := serve(mux, http.MethodGet, tc.target, "", nil)

			var body struct {
				Code    string `json:"code"`
				Details []struct {
					Field string `json:"field"`
				} `json:"details"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("status = %d, body is not JSON: %s", recorder.Code, recorder.Body)
			}
			if recorder.Code != http.StatusBadRequest || body.Code != tc.code || len(body.Details) != 1 || body.Details[0].Field != tc.field {
				t.Fatalf("status = %d, body = %s, want 400 %s on %s", recorder.Code, recorder.Body, tc.code, tc.field)
			}
			if len(usecases.commentList)+len(usecases.replyList) != 0 {
				t.Fatal("the usecase ran for a malformed request")
			}
		})
	}
}

func TestListCommentsHandler_AnswersNotFoundErrorsWithTheirCode(t *testing.T) {
	postID, parentID := uuid.NewString(), uuid.NewString()
	cases := []struct {
		name   string
		target string
		err    error
		code   string
	}{
		{"comments of a hidden post", "/api/content/posts/" + postID + "/comments", fmt.Errorf("list comments usecase: %w", usecase.ErrCommentsPostNotFound), "post_not_found"},
		{"replies of a hidden post", "/api/content/posts/" + postID + "/comments/" + parentID + "/replies", fmt.Errorf("list replies usecase: %w", usecase.ErrCommentsPostNotFound), "post_not_found"},
		{"replies of a hidden comment", "/api/content/posts/" + postID + "/comments/" + parentID + "/replies", fmt.Errorf("list replies usecase: %w", usecase.ErrCommentNotFound), "comment_not_found"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			mux := newCommentsMux(t, uuid.NewString(), &fakeUsecases{err: tc.err}, unsignedURLs{})

			recorder := serve(mux, http.MethodGet, tc.target, "", nil)

			var body struct {
				Code    string `json:"code"`
				Details []any  `json:"details"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil || recorder.Code != http.StatusNotFound || body.Code != tc.code || len(body.Details) != 0 {
				t.Fatalf("status = %d, body = %s, want 404 %s with no details", recorder.Code, recorder.Body, tc.code)
			}
		})
	}
}

func TestListCommentsHandler_Answers499AndSigningFailuresWithoutLeaking(t *testing.T) {
	postID := uuid.New()
	target := "/api/content/posts/" + postID.String() + "/comments"

	gone := newCommentsMux(t, uuid.NewString(), &fakeUsecases{err: fmt.Errorf("list comments usecase: %w", context.Canceled)}, unsignedURLs{})
	if recorder := serve(gone, http.MethodGet, target, "", nil); recorder.Code != 499 {
		t.Fatalf("status = %d, want 499 for a client that went away", recorder.Code)
	}

	failing := newCommentsMux(t, uuid.NewString(), &fakeUsecases{comments: sampleCommentPageOutput(postID)}, failingSigner{})
	recorder := serve(failing, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusInternalServerError || strings.Contains(recorder.Body.String(), "media.test") {
		t.Fatalf("status = %d, body = %s, want 500 without the unsigned url", recorder.Code, recorder.Body)
	}

	broken := newCommentsMux(t, uuid.NewString(), &fakeUsecases{err: errors.New("mongo failed")}, unsignedURLs{})
	recorder = serve(broken, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusInternalServerError || strings.Contains(recorder.Body.String(), "mongo") {
		t.Fatalf("status = %d, body = %s, want 500 without the internal error", recorder.Code, recorder.Body)
	}
}
