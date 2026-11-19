package presentation_test

import (
	"context"
	"encoding/json"
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

func newCollabRequestsMux(t *testing.T, subject string, usecases *fakeUsecases, signer usecase.MediaURLSigner) *http.ServeMux {
	t.Helper()

	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, subject), presentation.Routes{
		ListCollabRequests: presentation.NewListCollabRequestsHandler(fakeListCollabRequests{usecases}, signer, discardLogger()),
	})
	return mux
}

func collabRequestsTarget(collabID string) string {
	return "/api/content/collabs/" + collabID + "/requests"
}

func TestListCollabRequestsHandler_PassesTheCallerTheCollabAndThePendingDefaultAndAnswersThePage(t *testing.T) {
	userID, collabID := uuid.New(), uuid.New()
	picture, next := "https://media.test/ana.jpg", "next-page"
	usecases := &fakeUsecases{requestPage: usecase.CollabRequestPage{
		Items: []usecase.CollabRequestItem{{
			User:        usecase.FeedCreator{UserID: "44444444-4444-4444-8444-444444444444", Username: "ana", PictureURL: &picture},
			Status:      "PENDING",
			RequestedAt: time.Date(2026, 3, 1, 10, 15, 0, 500000000, time.UTC),
		}},
		NextCursor: &next,
	}}
	mux := newCollabRequestsMux(t, userID.String(), usecases, prefixSigner{})

	recorder := serve(mux, http.MethodGet, collabRequestsTarget(collabID.String()), "", map[string]string{presentation.CorrelationIDHeader: "corr-1"})

	if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("status = %d, content type %q: %s", recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body)
	}
	input := usecases.requests[0]
	if input.UserID != userID || input.CollabID != collabID || input.Status != "PENDING" || input.Limit != usecase.KeysetDefaultLimit || input.After != nil || input.CorrelationID != "corr-1" {
		t.Fatalf("input = %+v, want the caller, the path collab, PENDING, limit 20 and no cursor", input)
	}
	want := `{"items":[{"user":{"userId":"44444444-4444-4444-8444-444444444444","username":"ana","pictureUrl":"signed:https://media.test/ana.jpg"},"status":"PENDING","requestedAt":"2026-03-01T10:15:00.5Z"}],"nextCursor":"next-page"}`
	if got := strings.TrimSpace(recorder.Body.String()); got != want {
		t.Fatalf("body = %s\nwant %s", got, want)
	}
}

func TestListCollabRequestsHandler_PassesTheStatusTheDecodedCursorAndTheLimit(t *testing.T) {
	usecases := &fakeUsecases{}
	mux := newCollabRequestsMux(t, uuid.NewString(), usecases, unsignedURLs{})
	want := usecase.CollabRequestsCursor{RequestedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), UserID: uuid.NewString()}

	for _, status := range []string{"PENDING", "ACCEPTED", "REJECTED", "DELETED", "LEFT", "BANNED"} {
		target := collabRequestsTarget(uuid.NewString()) + "?status=" + status + "&limit=50&cursor=" + url.QueryEscape(usecase.EncodeCollabRequestsCursor(want))
		recorder := serve(mux, http.MethodGet, target, "", nil)

		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status = %d: %s", status, recorder.Code, recorder.Body)
		}
		input := usecases.requests[len(usecases.requests)-1]
		if input.Status != status || input.Limit != 50 || input.After == nil || *input.After != want {
			t.Fatalf("input = %+v, want %s, limit 50 and the decoded cursor", input, status)
		}
	}
}

func TestListCollabRequestsHandler_AnswersAnEmptyPage(t *testing.T) {
	mux := newCollabRequestsMux(t, uuid.NewString(), &fakeUsecases{}, unsignedURLs{})

	recorder := serve(mux, http.MethodGet, collabRequestsTarget(uuid.NewString()), "", nil)

	if got := strings.TrimSpace(recorder.Body.String()); recorder.Code != http.StatusOK || got != `{"items":[],"nextCursor":null}` {
		t.Fatalf("status = %d, body = %s", recorder.Code, got)
	}
}

func TestListCollabRequestsHandler_RejectsMalformedParametersWith400BeforeTheUsecase(t *testing.T) {
	collabID := uuid.NewString()
	gridCursor := usecase.EncodeGridCursor(usecase.GridCursor{CreatedAt: time.Now(), PostID: uuid.NewString()})
	cases := []struct {
		name, path, query, code string
	}{
		{"collab id not a uuid", "not-a-uuid", "", "invalid_collab_id"},
		{"nil collab id", uuid.Nil.String(), "", "invalid_collab_id"},
		{"hyphen-less collab id", strings.ReplaceAll(collabID, "-", ""), "", "invalid_collab_id"},
		{"unknown status", collabID, "?status=INVITED", "invalid_status"},
		{"lowercase status", collabID, "?status=pending", "invalid_status"},
		{"empty status", collabID, "?status=", "invalid_status"},
		{"garbage cursor", collabID, "?cursor=garbage!", "invalid_cursor"},
		{"empty cursor", collabID, "?cursor=", "invalid_cursor"},
		{"a grid cursor", collabID, "?cursor=" + gridCursor, "invalid_cursor"},
		{"zero limit", collabID, "?limit=0", "invalid_limit"},
		{"limit above 50", collabID, "?limit=51", "invalid_limit"},
		{"not a number", collabID, "?limit=ten", "invalid_limit"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			usecases := &fakeUsecases{}
			mux := newCollabRequestsMux(t, uuid.NewString(), usecases, unsignedURLs{})

			recorder := serve(mux, http.MethodGet, collabRequestsTarget(tc.path)+tc.query, "", nil)

			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, tc.code)
			if len(usecases.requests) != 0 {
				t.Fatal("the usecase ran for a malformed request")
			}
		})
	}
}

func TestListCollabRequestsHandler_AnswersNotFoundAndForbidden(t *testing.T) {
	cases := map[string]struct {
		err    error
		status int
		code   string
	}{
		"unknown collab": {usecase.ErrCollabNotFound, http.StatusNotFound, "collab_not_found"},
		"not the owner":  {usecase.ErrNotCollabOwner, http.StatusForbidden, "not_collab_owner"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			usecases := &fakeUsecases{err: fmt.Errorf("list collab requests usecase: %w", tc.err)}
			mux := newCollabRequestsMux(t, uuid.NewString(), usecases, unsignedURLs{})

			recorder := serve(mux, http.MethodGet, collabRequestsTarget(uuid.NewString()), "", nil)

			assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), tc.status, tc.code)
		})
	}
}

func TestListCollabRequestsHandler_AnswersASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	picture := "https://media.test/ana.jpg"
	usecases := &fakeUsecases{requestPage: usecase.CollabRequestPage{Items: []usecase.CollabRequestItem{{User: usecase.FeedCreator{UserID: uuid.NewString(), PictureURL: &picture}, Status: "PENDING"}}}}
	mux := newCollabRequestsMux(t, uuid.NewString(), usecases, failingSigner{})

	recorder := serve(mux, http.MethodGet, collabRequestsTarget(uuid.NewString()), "", nil)

	assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusInternalServerError, "internal_server_error")
	if strings.Contains(recorder.Body.String(), "delegation key") || strings.Contains(recorder.Body.String(), "media.test") {
		t.Fatalf("body leaks the cause: %s", recorder.Body)
	}
}

func TestListCollabRequestsHandler_AnswersACancelledRequestWith499(t *testing.T) {
	usecases := &fakeUsecases{err: context.Canceled}
	mux := newCollabRequestsMux(t, uuid.NewString(), usecases, unsignedURLs{})

	recorder := serve(mux, http.MethodGet, collabRequestsTarget(uuid.NewString()), "", nil)

	if recorder.Code != 499 {
		t.Fatalf("status = %d, want 499", recorder.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
}
