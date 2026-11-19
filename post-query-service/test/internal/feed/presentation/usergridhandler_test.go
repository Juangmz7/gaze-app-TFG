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

func sampleGridPage() usecase.GridPage {
	cover, next := "https://media.test/cover.jpg", "next-page"
	return usecase.GridPage{
		Items: []usecase.GridItem{{
			PostID: uuid.NewString(), CoverThumbnailURL: &cover, MediaType: "IMAGE", MediaCount: 2, PostType: "BASIC",
			LikeCount: 4, CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
		}},
		NextCursor: &next,
	}
}

func newGridMux(t *testing.T, subject string, usecases *fakeUsecases, signer usecase.MediaURLSigner) *http.ServeMux {
	t.Helper()

	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, subject), presentation.Routes{
		ListUserPosts:   presentation.NewListUserPostsHandler(fakeListUserPosts{usecases}, signer, discardLogger()),
		ListUserReposts: presentation.NewListUserRepostsHandler(fakeListUserReposts{usecases}, signer, discardLogger()),
	})
	return mux
}

// gridTargets are both grid paths of a user, with the inputs their usecase received.
func gridTargets(usecases *fakeUsecases, targetID string) map[string]func() []usecase.UserGridInput {
	return map[string]func() []usecase.UserGridInput{
		"/api/content/users/" + targetID + "/posts":   func() []usecase.UserGridInput { return usecases.postGrids },
		"/api/content/users/" + targetID + "/reposts": func() []usecase.UserGridInput { return usecases.repostGrids },
	}
}

func TestUserGridHandlers_PassTheCallerTheTargetAndTheDefaultLimitAndAnswerThePage(t *testing.T) {
	userID, targetID := uuid.New(), uuid.New()
	usecases := &fakeUsecases{grid: sampleGridPage()}
	mux := newGridMux(t, userID.String(), usecases, unsignedURLs{})

	for target, inputs := range gridTargets(usecases, targetID.String()) {
		t.Run(target, func(t *testing.T) {
			recorder := serve(mux, http.MethodGet, target, "", map[string]string{presentation.CorrelationIDHeader: "corr-1"})

			if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json" {
				t.Fatalf("status = %d, content type %q: %s", recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body)
			}
			input := inputs()[0]
			if input.UserID != userID || input.TargetUserID != targetID || input.Limit != usecase.KeysetDefaultLimit || input.After != nil || input.CorrelationID != "corr-1" {
				t.Fatalf("input = %+v, want the caller, the path user, limit 20 and no cursor", input)
			}
			var body struct {
				Items []map[string]any `json:"items"`
				Next  *string          `json:"nextCursor"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if len(body.Items) != 1 || body.Items[0]["mediaCount"] != float64(2) || body.Next == nil || *body.Next != "next-page" {
				t.Fatalf("body = %s", recorder.Body)
			}
		})
	}
}

func TestUserGridHandlers_PassTheDecodedCursorAndTheLimit(t *testing.T) {
	userID, targetID := uuid.New(), uuid.New()
	usecases := &fakeUsecases{}
	mux := newGridMux(t, userID.String(), usecases, unsignedURLs{})
	want := usecase.GridCursor{CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), PostID: uuid.NewString()}

	for target, inputs := range gridTargets(usecases, targetID.String()) {
		recorder := serve(mux, http.MethodGet, target+"?limit=50&cursor="+url.QueryEscape(usecase.EncodeGridCursor(want)), "", nil)

		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status = %d: %s", target, recorder.Code, recorder.Body)
		}
		if input := inputs()[0]; input.Limit != 50 || input.After == nil || *input.After != want {
			t.Fatalf("%s input = %+v, want limit 50 and the decoded cursor", target, input)
		}
	}
}

func TestUserGridHandlers_RejectMalformedParametersWith400BeforeTheUsecase(t *testing.T) {
	userID, targetID := uuid.New(), uuid.New().String()
	commentsCursor := usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: uuid.NewString()})
	cases := []struct {
		name, path, query, code string
	}{
		{"user id not a uuid", "not-a-uuid", "", "invalid_user_id"},
		{"nil user id", uuid.Nil.String(), "", "invalid_user_id"},
		{"hyphen-less user id", strings.ReplaceAll(targetID, "-", ""), "", "invalid_user_id"},
		{"urn user id", "urn:uuid:" + targetID, "", "invalid_user_id"},
		{"garbage cursor", targetID, "?cursor=garbage!", "invalid_cursor"},
		{"empty cursor", targetID, "?cursor=", "invalid_cursor"},
		{"a comments cursor", targetID, "?cursor=" + commentsCursor, "invalid_cursor"},
		{"zero limit", targetID, "?limit=0", "invalid_limit"},
		{"limit above 50", targetID, "?limit=51", "invalid_limit"},
		{"signed limit", targetID, "?limit=+5", "invalid_limit"},
		{"not a number", targetID, "?limit=ten", "invalid_limit"},
		{"empty limit", targetID, "?limit=", "invalid_limit"},
	}

	for _, grid := range []string{"posts", "reposts"} {
		for _, tc := range cases {
			t.Run(grid+" "+tc.name, func(t *testing.T) {
				usecases := &fakeUsecases{}
				mux := newGridMux(t, userID.String(), usecases, unsignedURLs{})

				recorder := serve(mux, http.MethodGet, fmt.Sprintf("/api/content/users/%s/%s%s", tc.path, grid, tc.query), "", nil)

				assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, tc.code)
				if len(usecases.postGrids)+len(usecases.repostGrids) != 0 {
					t.Fatal("the usecase ran for a malformed request")
				}
			})
		}
	}
}

func TestUserGridHandlers_AnswerUserNotFoundWith404(t *testing.T) {
	usecases := &fakeUsecases{err: fmt.Errorf("list: %w", usecase.ErrGridUserNotFound)}
	mux := newGridMux(t, uuid.NewString(), usecases, unsignedURLs{})

	for target := range gridTargets(usecases, uuid.NewString()) {
		recorder := serve(mux, http.MethodGet, target, "", nil)

		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "user_not_found")
		if !strings.Contains(recorder.Body.String(), "User not found") {
			t.Fatalf("%s body = %s", target, recorder.Body)
		}
	}
}

func TestUserGridHandlers_SignTheCoverAndAnswerASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	usecases := &fakeUsecases{grid: sampleGridPage()}
	signed := newGridMux(t, uuid.NewString(), usecases, prefixSigner{})
	failing := newGridMux(t, uuid.NewString(), usecases, failingSigner{})

	for target := range gridTargets(usecases, uuid.NewString()) {
		recorder := serve(signed, http.MethodGet, target, "", nil)
		if !strings.Contains(recorder.Body.String(), `"coverThumbnailUrl":"signed:https://media.test/cover.jpg"`) {
			t.Fatalf("%s body = %s, want the signed cover", target, recorder.Body)
		}

		recorder = serve(failing, http.MethodGet, target, "", nil)
		if recorder.Code != http.StatusInternalServerError || strings.Contains(recorder.Body.String(), "delegation key") || strings.Contains(recorder.Body.String(), "media.test") {
			t.Fatalf("%s status = %d: %s", target, recorder.Code, recorder.Body)
		}
	}
}

// prefixSigner marks every URL it signs.
type prefixSigner struct{}

func (prefixSigner) SignReadURL(_ context.Context, rawURL string) (string, error) {
	return "signed:" + rawURL, nil
}
