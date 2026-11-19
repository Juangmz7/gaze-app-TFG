package presentation_test

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
)

// newFollowingMux serves the following feed and the recommended feed, so a test can
// tell that only the following feed's usecase runs.
func newFollowingMux(t *testing.T, subject string, usecases *fakeUsecases, signer usecase.MediaURLSigner) *http.ServeMux {
	t.Helper()

	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, subject), presentation.Routes{
		ClaimFeed:     presentation.NewClaimFeedHandler(fakeClaim{usecases}, signer, discardLogger()),
		FollowingFeed: presentation.NewFollowingFeedHandler(fakeFollowingFeed{usecases}, signer, discardLogger()),
	})
	return mux
}

func sampleFollowingPage() usecase.FollowingFeedPage {
	next := "next-page"
	return usecase.FollowingFeedPage{Items: []usecase.FeedCard{samplePostCard(uuid.New())}, NextCursor: &next}
}

func TestFollowingFeedHandler_ReadsTheCallersFeedWithTheDefaultLimitAndAnswersPostViews(t *testing.T) {
	userID := uuid.New()
	usecases := &fakeUsecases{followPage: sampleFollowingPage()}
	mux := newFollowingMux(t, userID.String(), usecases, unsignedURLs{})

	recorder := serve(mux, http.MethodGet, "/api/feed/following", "", map[string]string{presentation.CorrelationIDHeader: "corr-1"})

	if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("status = %d, content type %q: %s", recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body)
	}
	want := usecase.FollowingFeedInput{UserID: userID, Limit: usecase.KeysetDefaultLimit, CorrelationID: "corr-1"}
	if len(usecases.following) != 1 || usecases.following[0] != want {
		t.Fatalf("inputs = %+v, want %+v", usecases.following, want)
	}
	if len(usecases.claims) != 0 {
		t.Fatal("the recommended feed's claim ran for the following feed")
	}

	var body struct {
		Items      []map[string]any `json:"items"`
		NextCursor *string          `json:"nextCursor"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(body.Items) != 1 || body.NextCursor == nil || *body.NextCursor != "next-page" {
		t.Fatalf("body = %s", recorder.Body)
	}
	item := body.Items[0]
	// Exactly the PostView of task 47: the feed item without position.
	fields := []string{"postId", "creator", "description", "postType", "postTags", "collabId", "collab", "createdAt", "media", "coverThumbnailUrl", "durationMs", "stats", "viewer"}
	if len(item) != len(fields) {
		t.Fatalf("item = %v, want exactly the PostView fields %v", item, fields)
	}
	for _, field := range fields {
		if _, found := item[field]; !found {
			t.Fatalf("item = %v, missing %s", item, field)
		}
	}
	collab, viewer := item["collab"].(map[string]any), item["viewer"].(map[string]any)
	if _, found := collab["pendingRequestCount"]; !found || collab["pendingRequestCount"] != nil {
		t.Fatalf("collab = %v, want pendingRequestCount null", collab)
	}
	if viewer["collabRequestStatus"] != nil || viewer["liked"] != true || viewer["followingCreator"] != true {
		t.Fatalf("viewer = %v", viewer)
	}
}

func TestFollowingFeedHandler_AnswersAnEmptyListAndANullCursorForAnEmptyPage(t *testing.T) {
	usecases := &fakeUsecases{followPage: usecase.FollowingFeedPage{Items: []usecase.FeedCard{}}}
	mux := newFollowingMux(t, uuid.NewString(), usecases, unsignedURLs{})

	recorder := serve(mux, http.MethodGet, "/api/feed/following", "", nil)

	if recorder.Code != http.StatusOK || strings.TrimSpace(recorder.Body.String()) != `{"items":[],"nextCursor":null}` {
		t.Fatalf("status = %d: %s, want 200 with items [] and nextCursor null", recorder.Code, recorder.Body)
	}
}

func TestFollowingFeedHandler_PassesTheDecodedCursorAndTheLimit(t *testing.T) {
	usecases := &fakeUsecases{}
	mux := newFollowingMux(t, uuid.NewString(), usecases, unsignedURLs{})
	want := usecase.GridCursor{CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), PostID: uuid.NewString()}

	recorder := serve(mux, http.MethodGet, "/api/feed/following?limit=50&cursor="+url.QueryEscape(usecase.EncodeGridCursor(want)), "", nil)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
	}
	if input := usecases.following[0]; input.Limit != 50 || input.After == nil || *input.After != want {
		t.Fatalf("input = %+v, want limit 50 and the decoded cursor", input)
	}
}

func TestFollowingFeedHandler_RejectsAMalformedCursorOrLimitWith400BeforeTheUsecase(t *testing.T) {
	commentsCursor := usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: uuid.NewString()})
	cases := map[string]string{
		"?cursor=garbage!":            "invalid_cursor",
		"?cursor=":                    "invalid_cursor",
		"?cursor=" + commentsCursor:   "invalid_cursor",
		"?cursor=" + uuid.NewString(): "invalid_cursor",
		"?limit=0":                    "invalid_limit",
		"?limit=51":                   "invalid_limit",
		"?limit=-1":                   "invalid_limit",
		"?limit=+5":                   "invalid_limit",
		"?limit=ten":                  "invalid_limit",
		"?limit=":                     "invalid_limit",
	}

	for query, code := range cases {
		t.Run(query, func(t *testing.T) {
			usecases := &fakeUsecases{}
			mux := newFollowingMux(t, uuid.NewString(), usecases, unsignedURLs{})

			recorder := serve(mux, http.MethodGet, "/api/feed/following"+query, "", nil)

			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
			if len(usecases.following)+len(usecases.claims) != 0 {
				t.Fatal("a usecase ran for a malformed request")
			}
		})
	}
}

func TestFollowingFeedHandler_SignsTheMediaAndAnswersASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	usecases := &fakeUsecases{followPage: sampleFollowingPage()}

	recorder := serve(newFollowingMux(t, uuid.NewString(), usecases, prefixSigner{}), http.MethodGet, "/api/feed/following", "", nil)
	if !strings.Contains(recorder.Body.String(), `"url":"signed:https://media.test/a.jpg"`) {
		t.Fatalf("body = %s, want the signed media url", recorder.Body)
	}

	recorder = serve(newFollowingMux(t, uuid.NewString(), usecases, failingSigner{}), http.MethodGet, "/api/feed/following", "", nil)
	if recorder.Code != http.StatusInternalServerError || strings.Contains(recorder.Body.String(), "delegation key") || strings.Contains(recorder.Body.String(), "media.test") {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
	}
}
