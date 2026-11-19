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

var ownListEpoch = time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

func sampleOwnPage() usecase.TimedGridPage {
	cover, next := "https://media.test/cover.jpg", "next-page"
	return usecase.TimedGridPage{
		Items: []usecase.TimedGridItem{{
			GridItem: usecase.GridItem{
				PostID: uuid.NewString(), CoverThumbnailURL: &cover, MediaType: "IMAGE", MediaCount: 2, PostType: "BASIC",
				LikeCount: 4, CreatedAt: ownListEpoch,
			},
			At: ownListEpoch.Add(time.Hour),
		}},
		NextCursor: &next,
	}
}

func newOwnListMux(t *testing.T, subject string, usecases *fakeUsecases, signer usecase.MediaURLSigner) *http.ServeMux {
	t.Helper()

	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, subject), presentation.Routes{
		ListMyLiked:   presentation.NewListMyLikedHandler(fakeListMyLiked{usecases}, signer, discardLogger()),
		ListMyHistory: presentation.NewListMyHistoryHandler(fakeListMyHistory{usecases}, signer, discardLogger()),
	})
	return mux
}

// ownListTargets are both own tab paths, with the inputs their usecase received and the
// name of their item's time.
func ownListTargets(usecases *fakeUsecases) map[string]struct {
	inputs   func() []usecase.OwnListInput
	timeName string
} {
	return map[string]struct {
		inputs   func() []usecase.OwnListInput
		timeName string
	}{
		"/api/content/me/liked":   {func() []usecase.OwnListInput { return usecases.likedLists }, "likedAt"},
		"/api/content/me/history": {func() []usecase.OwnListInput { return usecases.historyList }, "viewedAt"},
	}
}

func TestOwnListHandlers_ReadTheCallersListWithTheDefaultLimitAndAnswerGridItemsWithTheirTime(t *testing.T) {
	userID := uuid.New()
	usecases := &fakeUsecases{ownPage: sampleOwnPage()}
	mux := newOwnListMux(t, userID.String(), usecases, unsignedURLs{})

	for target, list := range ownListTargets(usecases) {
		t.Run(target, func(t *testing.T) {
			recorder := serve(mux, http.MethodGet, target, "", map[string]string{presentation.CorrelationIDHeader: "corr-1"})

			if recorder.Code != http.StatusOK || recorder.Header().Get("Content-Type") != "application/json" {
				t.Fatalf("status = %d, content type %q: %s", recorder.Code, recorder.Header().Get("Content-Type"), recorder.Body)
			}
			input := list.inputs()[0]
			if input.UserID != userID || input.Limit != usecase.KeysetDefaultLimit || input.After != nil || input.CorrelationID != "corr-1" {
				t.Fatalf("input = %+v, want the caller, limit 20 and no cursor", input)
			}

			var body struct {
				Items []map[string]any `json:"items"`
				Next  *string          `json:"nextCursor"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if len(body.Items) != 1 || body.Next == nil || *body.Next != "next-page" {
				t.Fatalf("body = %s", recorder.Body)
			}
			want := map[string]any{
				"postId": usecases.ownPage.Items[0].PostID, "coverThumbnailUrl": "https://media.test/cover.jpg", "mediaType": "IMAGE",
				"mediaCount": float64(2), "postType": "BASIC", "collabStatus": nil, "likeCount": float64(4),
				"createdAt": "2026-03-01T10:00:00Z", list.timeName: "2026-03-01T11:00:00Z",
			}
			if len(body.Items[0]) != len(want) {
				t.Fatalf("item = %v, want exactly the fields %v", body.Items[0], want)
			}
			for field, value := range want {
				if got, found := body.Items[0][field]; !found || got != value {
					t.Fatalf("item[%s] = %v (present %v), want %v", field, got, found, value)
				}
			}
		})
	}
}

func TestOwnListHandlers_PassTheDecodedCursorAndTheLimit(t *testing.T) {
	usecases := &fakeUsecases{}
	mux := newOwnListMux(t, uuid.NewString(), usecases, unsignedURLs{})
	want := usecase.GridCursor{CreatedAt: ownListEpoch, PostID: uuid.NewString()}

	for target, list := range ownListTargets(usecases) {
		recorder := serve(mux, http.MethodGet, target+"?limit=50&cursor="+url.QueryEscape(usecase.EncodeGridCursor(want)), "", nil)

		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status = %d: %s", target, recorder.Code, recorder.Body)
		}
		if input := list.inputs()[0]; input.Limit != 50 || input.After == nil || *input.After != want {
			t.Fatalf("%s input = %+v, want limit 50 and the decoded cursor", target, input)
		}
	}
}

func TestOwnListHandlers_RejectAMalformedCursorOrLimitWith400BeforeTheUsecase(t *testing.T) {
	commentsCursor := usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: uuid.NewString()})
	cases := map[string]string{
		"?cursor=garbage!":          "invalid_cursor",
		"?cursor=":                  "invalid_cursor",
		"?cursor=" + commentsCursor: "invalid_cursor",
		"?limit=0":                  "invalid_limit",
		"?limit=51":                 "invalid_limit",
		"?limit=+5":                 "invalid_limit",
		"?limit=ten":                "invalid_limit",
		"?limit=":                   "invalid_limit",
	}

	for _, target := range []string{"/api/content/me/liked", "/api/content/me/history"} {
		for query, code := range cases {
			t.Run(target+query, func(t *testing.T) {
				usecases := &fakeUsecases{}
				mux := newOwnListMux(t, uuid.NewString(), usecases, unsignedURLs{})

				recorder := serve(mux, http.MethodGet, target+query, "", nil)

				assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
				if len(usecases.likedLists)+len(usecases.historyList) != 0 {
					t.Fatal("the usecase ran for a malformed request")
				}
			})
		}
	}
}

func TestOwnListHandlers_OfferNoRouteToAnotherUsersList(t *testing.T) {
	usecases := &fakeUsecases{}
	mux := newOwnListMux(t, uuid.NewString(), usecases, unsignedURLs{})

	for _, target := range []string{
		"/api/content/users/" + uuid.NewString() + "/liked",
		"/api/content/users/" + uuid.NewString() + "/history",
		"/api/content/me/" + uuid.NewString() + "/liked",
	} {
		if recorder := serve(mux, http.MethodGet, target, "", nil); recorder.Code != http.StatusNotFound {
			t.Fatalf("GET %s status = %d, want 404: no such route", target, recorder.Code)
		}
	}
	if len(usecases.likedLists)+len(usecases.historyList) != 0 {
		t.Fatal("a usecase ran for another user's list")
	}
}

func TestOwnListHandlers_SignTheCoverAndAnswerASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	usecases := &fakeUsecases{ownPage: sampleOwnPage()}
	signed := newOwnListMux(t, uuid.NewString(), usecases, prefixSigner{})
	failing := newOwnListMux(t, uuid.NewString(), usecases, failingSigner{})

	for target := range ownListTargets(usecases) {
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
