package presentation_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
)

func samplePostCard(postID uuid.UUID) usecase.FeedCard {
	description := "Harbour at dusk"
	return usecase.FeedCard{
		PostID:      postID.String(),
		Position:    42,
		Creator:     usecase.FeedCreator{UserID: uuid.NewString(), Username: "ana_rivera"},
		Description: &description,
		PostType:    usecase.FeedPostTypeBasic,
		Tags:        []string{"travel"},
		CreatedAt:   time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
		Media:       []usecase.FeedMedia{{ID: uuid.NewString(), URL: "https://media.test/a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 1}},
		Viewer:      usecase.FeedViewerState{Liked: true, FollowingCreator: true},
	}
}

func TestGetPostHandler_AnswersThePostViewOfTheCallerAndThePathID(t *testing.T) {
	userID, postID := uuid.New(), uuid.New()
	mux, usecases := newMux(withSubject(t, userID.String()))
	usecases.post = samplePostCard(postID)

	recorder := serve(mux, http.MethodGet, "/api/content/posts/"+postID.String(), "", map[string]string{presentation.CorrelationIDHeader: "client-corr-7"})

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", recorder.Code, recorder.Body)
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", got)
	}
	want := usecase.GetPostInput{UserID: userID, PostID: postID, CorrelationID: "client-corr-7"}
	if len(usecases.postReads) != 1 || usecases.postReads[0] != want {
		t.Fatalf("usecase input = %+v, want %+v", usecases.postReads, want)
	}

	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if _, found := body["position"]; found {
		t.Fatalf("body = %v, want no position in a post view", body)
	}
	if body["postId"] != postID.String() || body["description"] != "Harbour at dusk" {
		t.Fatalf("body = %v", body)
	}
	viewer := body["viewer"].(map[string]any)
	if viewer["liked"] != true || viewer["shared"] != false || viewer["followingCreator"] != true || viewer["collabRequestStatus"] != nil {
		t.Fatalf("viewer = %v", viewer)
	}
}

func TestGetPostHandler_RejectsAPostIDThatIsNotACanonicalUUID(t *testing.T) {
	cases := map[string]string{
		"not a uuid":   "harbour-at-dusk",
		"urn form":     "urn:uuid:" + uuid.NewString(),
		"braced form":  "%7B" + uuid.NewString() + "%7D",
		"no hyphens":   strings.ReplaceAll(uuid.NewString(), "-", ""),
		"the nil uuid": uuid.Nil.String(),
	}

	for name, postID := range cases {
		t.Run(name, func(t *testing.T) {
			mux, usecases := newMux(withSubject(t, uuid.NewString()))

			recorder := serve(mux, http.MethodGet, "/api/content/posts/"+postID, "", nil)

			if recorder.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400: %s", recorder.Code, recorder.Body)
			}
			var body common.ApiErrorResponse
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if body.Code != "invalid_post_id" || len(body.Details) != 1 || body.Details[0].Field != "postId" {
				t.Fatalf("body = %+v, want invalid_post_id on postId", body)
			}
			if len(usecases.postReads) != 0 {
				t.Fatal("the usecase ran for a malformed id")
			}
		})
	}
}

func TestGetPostHandler_AnswersAHiddenPostWith404PostNotFound(t *testing.T) {
	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	usecases.err = fmt.Errorf("get post usecase: %w", usecase.ErrGetPostNotFound)

	recorder := serve(mux, http.MethodGet, "/api/content/posts/"+uuid.NewString(), "", nil)

	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404: %s", recorder.Code, recorder.Body)
	}
	var body common.ApiErrorResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if body.Code != "post_not_found" || body.Status != http.StatusNotFound {
		t.Fatalf("body = %+v, want post_not_found", body)
	}
}

func TestGetPostHandler_DoesNotReportAClientThatWentAwayAsAServerFailure(t *testing.T) {
	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	usecases.err = fmt.Errorf("get post usecase: find in posts: %w", context.Canceled)

	if recorder := serve(mux, http.MethodGet, "/api/content/posts/"+uuid.NewString(), "", nil); recorder.Code != 499 {
		t.Fatalf("status = %d, want 499", recorder.Code)
	}
}

func TestGetPostHandler_AnswersASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	postID := uuid.New()
	usecases := &fakeUsecases{post: samplePostCard(postID)}
	usecases.post.Media[0].URL = "http://azurite:10000/devstoreaccount1/post-media-dev/m1"
	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, uuid.NewString()), presentation.Routes{
		Heartbeat:  presentation.NewHeartbeatHandler(fakeHeartbeat{usecases}, discardLogger()),
		Disconnect: presentation.NewDisconnectHandler(fakeDisconnect{usecases}, discardLogger()),
		ClaimFeed:  presentation.NewClaimFeedHandler(fakeClaim{usecases}, unsignedURLs{}, discardLogger()),
		GetPost:    presentation.NewGetPostHandler(fakeGetPost{usecases}, failingSigner{}, discardLogger()),
	})

	recorder := serve(mux, http.MethodGet, "/api/content/posts/"+postID.String(), "", nil)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", recorder.Code)
	}
	if body := recorder.Body.String(); strings.Contains(body, "delegation") || strings.Contains(body, "azurite") {
		t.Fatalf("body leaks the internal error or an unsigned url: %s", body)
	}
}
