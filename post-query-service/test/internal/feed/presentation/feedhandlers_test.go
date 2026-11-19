package presentation_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/lestrrat-go/jwx/v2/jwt"
	oteltrace "go.opentelemetry.io/otel/trace"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/health"
)

type contextKey struct{}

type fakeUsecases struct {
	heartbeats  []usecase.RegisterHeartbeatInput
	disconnects []usecase.RegisterUserDisconnectedInput
	claims      []usecase.ClaimFeedInput
	postReads   []usecase.GetPostInput
	commentList []usecase.ListCommentsInput
	replyList   []usecase.ListRepliesInput
	postGrids   []usecase.UserGridInput
	repostGrids []usecase.UserGridInput
	likedLists  []usecase.OwnListInput
	historyList []usecase.OwnListInput
	following   []usecase.FollowingFeedInput
	requests    []usecase.ListCollabRequestsInput
	gotCtx      context.Context
	err         error
	output      usecase.ClaimFeedOutput
	post        usecase.FeedCard
	comments    usecase.CommentPage
	grid        usecase.GridPage
	ownPage     usecase.TimedGridPage
	followPage  usecase.FollowingFeedPage
	requestPage usecase.CollabRequestPage
}

type fakeHeartbeat struct{ *fakeUsecases }

func (f fakeHeartbeat) Execute(ctx context.Context, input usecase.RegisterHeartbeatInput) error {
	f.heartbeats = append(f.heartbeats, input)
	f.gotCtx = ctx
	return f.err
}

type fakeDisconnect struct{ *fakeUsecases }

func (f fakeDisconnect) Execute(ctx context.Context, input usecase.RegisterUserDisconnectedInput) error {
	f.disconnects = append(f.disconnects, input)
	f.gotCtx = ctx
	return f.err
}

type fakeClaim struct{ *fakeUsecases }

func (f fakeClaim) Execute(ctx context.Context, input usecase.ClaimFeedInput) (usecase.ClaimFeedOutput, error) {
	f.claims = append(f.claims, input)
	f.gotCtx = ctx
	return f.output, f.err
}

type fakeGetPost struct{ *fakeUsecases }

func (f fakeGetPost) Execute(ctx context.Context, input usecase.GetPostInput) (usecase.FeedCard, error) {
	f.postReads = append(f.postReads, input)
	f.gotCtx = ctx
	return f.post, f.err
}

type fakeListComments struct{ *fakeUsecases }

func (f fakeListComments) Execute(ctx context.Context, input usecase.ListCommentsInput) (usecase.CommentPage, error) {
	f.commentList = append(f.commentList, input)
	f.gotCtx = ctx
	return f.comments, f.err
}

type fakeListReplies struct{ *fakeUsecases }

func (f fakeListReplies) Execute(ctx context.Context, input usecase.ListRepliesInput) (usecase.CommentPage, error) {
	f.replyList = append(f.replyList, input)
	f.gotCtx = ctx
	return f.comments, f.err
}

type fakeListUserPosts struct{ *fakeUsecases }

func (f fakeListUserPosts) Execute(ctx context.Context, input usecase.UserGridInput) (usecase.GridPage, error) {
	f.postGrids = append(f.postGrids, input)
	f.gotCtx = ctx
	return f.grid, f.err
}

type fakeListUserReposts struct{ *fakeUsecases }

func (f fakeListUserReposts) Execute(ctx context.Context, input usecase.UserGridInput) (usecase.GridPage, error) {
	f.repostGrids = append(f.repostGrids, input)
	f.gotCtx = ctx
	return f.grid, f.err
}

type fakeListMyLiked struct{ *fakeUsecases }

func (f fakeListMyLiked) Execute(ctx context.Context, input usecase.OwnListInput) (usecase.TimedGridPage, error) {
	f.likedLists = append(f.likedLists, input)
	f.gotCtx = ctx
	return f.ownPage, f.err
}

type fakeListMyHistory struct{ *fakeUsecases }

func (f fakeListMyHistory) Execute(ctx context.Context, input usecase.OwnListInput) (usecase.TimedGridPage, error) {
	f.historyList = append(f.historyList, input)
	f.gotCtx = ctx
	return f.ownPage, f.err
}

type fakeFollowingFeed struct{ *fakeUsecases }

func (f fakeFollowingFeed) Execute(ctx context.Context, input usecase.FollowingFeedInput) (usecase.FollowingFeedPage, error) {
	f.following = append(f.following, input)
	f.gotCtx = ctx
	return f.followPage, f.err
}

type fakeListCollabRequests struct{ *fakeUsecases }

func (f fakeListCollabRequests) Execute(ctx context.Context, input usecase.ListCollabRequestsInput) (usecase.CollabRequestPage, error) {
	f.requests = append(f.requests, input)
	f.gotCtx = ctx
	return f.requestPage, f.err
}

func newMux(authenticate func(http.Handler) http.Handler) (*http.ServeMux, *fakeUsecases) {
	usecases := &fakeUsecases{}
	mux := http.NewServeMux()
	health.Register(mux, nil)
	presentation.RegisterRoutes(mux, authenticate, presentation.Routes{
		Heartbeat:          presentation.NewHeartbeatHandler(fakeHeartbeat{usecases}, discardLogger()),
		Disconnect:         presentation.NewDisconnectHandler(fakeDisconnect{usecases}, discardLogger()),
		ClaimFeed:          presentation.NewClaimFeedHandler(fakeClaim{usecases}, unsignedURLs{}, discardLogger()),
		FollowingFeed:      presentation.NewFollowingFeedHandler(fakeFollowingFeed{usecases}, unsignedURLs{}, discardLogger()),
		GetPost:            presentation.NewGetPostHandler(fakeGetPost{usecases}, unsignedURLs{}, discardLogger()),
		ListComments:       presentation.NewListCommentsHandler(fakeListComments{usecases}, unsignedURLs{}, discardLogger()),
		ListReplies:        presentation.NewListRepliesHandler(fakeListReplies{usecases}, unsignedURLs{}, discardLogger()),
		ListUserPosts:      presentation.NewListUserPostsHandler(fakeListUserPosts{usecases}, unsignedURLs{}, discardLogger()),
		ListUserReposts:    presentation.NewListUserRepostsHandler(fakeListUserReposts{usecases}, unsignedURLs{}, discardLogger()),
		ListMyLiked:        presentation.NewListMyLikedHandler(fakeListMyLiked{usecases}, unsignedURLs{}, discardLogger()),
		ListMyHistory:      presentation.NewListMyHistoryHandler(fakeListMyHistory{usecases}, unsignedURLs{}, discardLogger()),
		ListCollabRequests: presentation.NewListCollabRequestsHandler(fakeListCollabRequests{usecases}, unsignedURLs{}, discardLogger()),
	})
	return mux, usecases
}

var feedEndpoints = []struct {
	method string
	target string
	status int
}{
	{http.MethodPost, "/api/feed/heartbeat", http.StatusNoContent},
	{http.MethodPost, "/api/feed/disconnect", http.StatusAccepted},
	{http.MethodGet, "/api/feed", http.StatusOK},
	{http.MethodGet, "/api/feed/following", http.StatusOK},
	{http.MethodGet, "/api/content/posts/" + uuid.NewString(), http.StatusOK},
	{http.MethodGet, "/api/content/posts/" + uuid.NewString() + "/comments", http.StatusOK},
	{http.MethodGet, "/api/content/posts/" + uuid.NewString() + "/comments/" + uuid.NewString() + "/replies", http.StatusOK},
	{http.MethodGet, "/api/content/users/" + uuid.NewString() + "/posts", http.StatusOK},
	{http.MethodGet, "/api/content/users/" + uuid.NewString() + "/reposts", http.StatusOK},
	{http.MethodGet, "/api/content/me/liked", http.StatusOK},
	{http.MethodGet, "/api/content/me/history", http.StatusOK},
	{http.MethodGet, "/api/content/collabs/" + uuid.NewString() + "/requests", http.StatusOK},
}

func TestFeedEndpoints_RespondWithTheirStatusForAValidToken(t *testing.T) {
	issuer := newTokenIssuer(t)
	mux, usecases := newMux(issuer.authenticate)
	userID := uuid.New()
	token := issuer.tokenFor(t, userID.String())

	for _, endpoint := range feedEndpoints {
		t.Run(endpoint.target, func(t *testing.T) {
			recorder := serve(mux, endpoint.method, endpoint.target, token, nil)
			if recorder.Code != endpoint.status {
				t.Fatalf("status = %d, want %d: %s", recorder.Code, endpoint.status, recorder.Body)
			}
		})
	}

	if len(usecases.heartbeats) != 1 || usecases.heartbeats[0].UserID != userID {
		t.Fatalf("heartbeats = %+v", usecases.heartbeats)
	}
	if len(usecases.disconnects) != 1 || usecases.disconnects[0].UserID != userID {
		t.Fatalf("disconnects = %+v", usecases.disconnects)
	}
	if len(usecases.claims) != 1 || usecases.claims[0].UserID != userID {
		t.Fatalf("claims = %+v", usecases.claims)
	}
	if len(usecases.postReads) != 1 || usecases.postReads[0].UserID != userID {
		t.Fatalf("post reads = %+v", usecases.postReads)
	}
	if len(usecases.commentList) != 1 || usecases.commentList[0].UserID != userID || len(usecases.replyList) != 1 || usecases.replyList[0].UserID != userID {
		t.Fatalf("comment lists = %+v, replies = %+v", usecases.commentList, usecases.replyList)
	}
	if len(usecases.postGrids) != 1 || usecases.postGrids[0].UserID != userID || len(usecases.repostGrids) != 1 || usecases.repostGrids[0].UserID != userID {
		t.Fatalf("post grids = %+v, repost grids = %+v", usecases.postGrids, usecases.repostGrids)
	}
	if len(usecases.likedLists) != 1 || usecases.likedLists[0].UserID != userID || len(usecases.historyList) != 1 || usecases.historyList[0].UserID != userID {
		t.Fatalf("liked lists = %+v, history lists = %+v", usecases.likedLists, usecases.historyList)
	}
	if len(usecases.following) != 1 || usecases.following[0].UserID != userID {
		t.Fatalf("following feeds = %+v", usecases.following)
	}
	if len(usecases.requests) != 1 || usecases.requests[0].UserID != userID {
		t.Fatalf("collab requests lists = %+v", usecases.requests)
	}
}

func TestFeedEndpoints_ReturnUnauthorizedWithoutAValidToken(t *testing.T) {
	issuer := newTokenIssuer(t)
	mux, usecases := newMux(issuer.authenticate)

	otherIssuer := issuer.sign(t, func(builder *jwt.Builder) *jwt.Builder {
		return builder.Issuer("https://evil.test").Subject(uuid.NewString())
	})
	otherAudience := issuer.sign(t, func(builder *jwt.Builder) *jwt.Builder {
		return builder.Audience([]string{"another-api"}).Subject(uuid.NewString())
	})
	expired := issuer.sign(t, func(builder *jwt.Builder) *jwt.Builder {
		return builder.Expiration(jwtPast()).IssuedAt(jwtPast()).Subject(uuid.NewString())
	})
	noSubject := issuer.sign(t, func(builder *jwt.Builder) *jwt.Builder { return builder })

	tokens := map[string]string{
		"no token":              "",
		"garbage":               "not-a-token",
		"another issuer":        otherIssuer,
		"another audience":      otherAudience,
		"expired":               expired,
		"no subject":            noSubject,
		"subject is not a UUID": issuer.tokenFor(t, "service-account-mobile-app"),
		"subject is a nil UUID": issuer.tokenFor(t, uuid.Nil.String()),
		"subject is a URN UUID": issuer.tokenFor(t, "urn:uuid:"+uuid.NewString()),
	}

	for name, token := range tokens {
		for _, endpoint := range feedEndpoints {
			t.Run(name+" "+endpoint.target, func(t *testing.T) {
				recorder := serve(mux, endpoint.method, endpoint.target, token, nil)
				if recorder.Code != http.StatusUnauthorized {
					t.Fatalf("status = %d, want 401: %s", recorder.Code, recorder.Body)
				}
			})
		}
	}

	if len(usecases.heartbeats)+len(usecases.disconnects)+len(usecases.claims)+len(usecases.postReads)+len(usecases.commentList)+len(usecases.replyList)+len(usecases.postGrids)+len(usecases.repostGrids)+len(usecases.likedLists)+len(usecases.historyList)+len(usecases.following)+len(usecases.requests) != 0 {
		t.Fatal("a usecase ran without a valid token")
	}
}

func TestFeedEndpoints_IgnoreAUserIDSuppliedInTheRequest(t *testing.T) {
	issuer := newTokenIssuer(t)
	mux, usecases := newMux(issuer.authenticate)
	userID, attacker := uuid.New(), uuid.New()
	token := issuer.tokenFor(t, userID.String())

	for _, endpoint := range feedEndpoints {
		request := httptest.NewRequest(endpoint.method, endpoint.target+"?userId="+attacker.String(), strings.NewReader(`{"userId":"`+attacker.String()+`"}`))
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("X-User-Id", attacker.String())
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder, request)
		if recorder.Code != endpoint.status {
			t.Fatalf("%s status = %d: %s", endpoint.target, recorder.Code, recorder.Body)
		}
	}

	if usecases.heartbeats[0].UserID != userID || usecases.disconnects[0].UserID != userID || usecases.claims[0].UserID != userID || usecases.postReads[0].UserID != userID ||
		usecases.commentList[0].UserID != userID || usecases.replyList[0].UserID != userID || usecases.postGrids[0].UserID != userID || usecases.repostGrids[0].UserID != userID ||
		usecases.likedLists[0].UserID != userID || usecases.historyList[0].UserID != userID || usecases.following[0].UserID != userID ||
		usecases.requests[0].UserID != userID {
		t.Fatalf("usecases ran for another user: %+v %+v %+v %+v %+v %+v", usecases.heartbeats, usecases.disconnects, usecases.claims, usecases.postReads, usecases.commentList, usecases.replyList)
	}
}

func TestHealthEndpoints_StayUnauthenticated(t *testing.T) {
	mux, _ := newMux(newTokenIssuer(t).authenticate)

	if recorder := serve(mux, http.MethodGet, health.LivePath, "", nil); recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
}

func TestFeedEndpoints_UseTheCorrelationIDHeaderOrGenerateOne(t *testing.T) {
	userID := uuid.New()
	mux, usecases := newMux(withSubject(t, userID.String()))

	recorder := serve(mux, http.MethodPost, "/api/feed/heartbeat", "", map[string]string{presentation.CorrelationIDHeader: "client-corr-1"})
	if got := recorder.Header().Get(presentation.CorrelationIDHeader); got != "client-corr-1" {
		t.Fatalf("response header = %q", got)
	}
	if usecases.heartbeats[0].CorrelationID != "client-corr-1" {
		t.Fatalf("usecase correlation id = %q", usecases.heartbeats[0].CorrelationID)
	}

	for name, header := range map[string]map[string]string{
		"absent":    nil,
		"too long":  {presentation.CorrelationIDHeader: strings.Repeat("a", 129)},
		"has space": {presentation.CorrelationIDHeader: "a b"},
	} {
		t.Run(name, func(t *testing.T) {
			recorder := serve(mux, http.MethodPost, "/api/feed/disconnect", "", header)
			generated := recorder.Header().Get(presentation.CorrelationIDHeader)
			if _, err := uuid.Parse(generated); err != nil {
				t.Fatalf("response header = %q, want a generated UUID", generated)
			}
			if usecases.disconnects[len(usecases.disconnects)-1].CorrelationID != generated {
				t.Fatalf("usecase correlation id differs from the response's %q", generated)
			}
		})
	}
}

func TestFeedEndpoints_AnswerAUsecaseFailureWithTheSharedErrorBody(t *testing.T) {
	for _, endpoint := range feedEndpoints {
		t.Run(endpoint.target, func(t *testing.T) {
			mux, usecases := newMux(withSubject(t, uuid.NewString()))
			usecases.err = errors.New("mongo failed")

			recorder := serve(mux, endpoint.method, endpoint.target, "", nil)
			if recorder.Code != http.StatusInternalServerError {
				t.Fatalf("status = %d, want 500", recorder.Code)
			}
			if strings.Contains(recorder.Body.String(), "mongo failed") {
				t.Fatalf("body leaks the internal error: %s", recorder.Body)
			}
			var body struct {
				Status int    `json:"status"`
				Code   string `json:"code"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("body is not the shared error shape: %v", err)
			}
			if body.Status != http.StatusInternalServerError || body.Code != "internal_server_error" {
				t.Fatalf("body = %+v", body)
			}
		})
	}
}

func TestFeedEndpoints_DoNotReportAClientThatWentAwayAsAServerFailure(t *testing.T) {
	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	usecases.err = context.Canceled

	if recorder := serve(mux, http.MethodGet, "/api/feed", "", nil); recorder.Code != 499 {
		t.Fatalf("status = %d, want 499", recorder.Code)
	}
}

func TestClaimFeedHandler_AnswersADegradedFeedWith503AndRetryAfter(t *testing.T) {
	tests := map[string]struct {
		retryAfter time.Duration
		want       string
	}{
		"whole seconds":                 {7 * time.Second, "7"},
		"a fraction rounds up, never 0": {1500 * time.Millisecond, "2"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			mux, usecases := newMux(withSubject(t, uuid.NewString()))
			usecases.err = fmt.Errorf("claim feed usecase: %w", &usecase.FeedTemporarilyUnavailableError{RetryAfter: test.retryAfter, Cause: errors.New("dial tcp 10.1.2.3:50051: refused")})

			recorder := serve(mux, http.MethodGet, "/api/feed", "", nil)

			if recorder.Code != http.StatusServiceUnavailable {
				t.Fatalf("status = %d, want 503", recorder.Code)
			}
			if got := recorder.Header().Get("Retry-After"); got != test.want {
				t.Fatalf("Retry-After = %q, want %q", got, test.want)
			}
			var body common.ApiErrorResponse
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if body.Code != "feed_temporarily_unavailable" || body.Status != http.StatusServiceUnavailable {
				t.Fatalf("body = %+v", body)
			}
			if strings.Contains(recorder.Body.String(), "10.1.2.3") {
				t.Fatalf("the body leaks the cause: %s", recorder.Body)
			}
		})
	}
}

func TestFeedEndpoints_ContinueTheCallersTraceWhenItIsValid(t *testing.T) {
	const traceID = "4bf92f3577b34da6a3ce929d0e0e4736"

	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	serve(mux, http.MethodGet, "/api/feed", "", map[string]string{"traceparent": "00-" + traceID + "-00f067aa0ba902b7-01"})
	if got := oteltrace.SpanContextFromContext(usecases.gotCtx).TraceID().String(); got != traceID {
		t.Fatalf("trace id = %q, want %q", got, traceID)
	}

	serve(mux, http.MethodGet, "/api/feed", "", map[string]string{"traceparent": "not-a-trace"})
	if oteltrace.SpanContextFromContext(usecases.gotCtx).IsValid() {
		t.Fatal("an invalid traceparent must be ignored")
	}
}

func TestFeedEndpoints_PropagateTheRequestContext(t *testing.T) {
	for _, endpoint := range feedEndpoints {
		t.Run(endpoint.target, func(t *testing.T) {
			mux, usecases := newMux(withSubject(t, uuid.NewString()))

			request := httptest.NewRequest(endpoint.method, endpoint.target, nil)
			request = request.WithContext(context.WithValue(request.Context(), contextKey{}, "propagated"))
			mux.ServeHTTP(httptest.NewRecorder(), request)

			if usecases.gotCtx == nil || usecases.gotCtx.Value(contextKey{}) != "propagated" {
				t.Fatal("the usecase did not receive the request context")
			}
		})
	}
}

func TestClaimFeedHandler_ParsesTheCursorAndTheLimit(t *testing.T) {
	cursor := uuid.New()

	t.Run("both omitted", func(t *testing.T) {
		mux, usecases := newMux(withSubject(t, uuid.NewString()))
		if recorder := serve(mux, http.MethodGet, "/api/feed", "", nil); recorder.Code != http.StatusOK {
			t.Fatalf("status = %d", recorder.Code)
		}
		if usecases.claims[0].LastSeenID != nil || usecases.claims[0].Limit != nil {
			t.Fatalf("input = %+v, want a null cursor and limit", usecases.claims[0])
		}
	})

	t.Run("both present", func(t *testing.T) {
		mux, usecases := newMux(withSubject(t, uuid.NewString()))
		if recorder := serve(mux, http.MethodGet, "/api/feed?limit=7&lastSeenId="+cursor.String(), "", nil); recorder.Code != http.StatusOK {
			t.Fatalf("status = %d", recorder.Code)
		}
		input := usecases.claims[0]
		if input.LastSeenID == nil || *input.LastSeenID != cursor || input.Limit == nil || *input.Limit != 7 {
			t.Fatalf("input = %+v", input)
		}
	})

	for _, query := range []string{
		"lastSeenId=not-a-uuid",
		"lastSeenId=",
		"lastSeenId=" + strings.ReplaceAll(cursor.String(), "-", ""),
		"lastSeenId=urn:uuid:" + cursor.String(),
		"limit=abc",
		"limit=",
		"limit=1.5",
	} {
		t.Run(query, func(t *testing.T) {
			mux, usecases := newMux(withSubject(t, uuid.NewString()))
			if recorder := serve(mux, http.MethodGet, "/api/feed?"+query, "", nil); recorder.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400", recorder.Code)
			}
			if len(usecases.claims) != 0 {
				t.Fatal("the usecase ran for a malformed request")
			}
		})
	}
}

func TestClaimFeedHandler_MapsAnInvalidLimitTo400(t *testing.T) {
	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	usecases.err = usecase.ErrClaimFeedInvalidLimit

	recorder := serve(mux, http.MethodGet, "/api/feed?limit=500", "", nil)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", recorder.Code)
	}
	var body struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil || body.Code != "invalid_limit" {
		t.Fatalf("body = %s (decode error %v)", recorder.Body, err)
	}
}

func TestClaimFeedHandler_WritesThePageAsJSON(t *testing.T) {
	mux, usecases := newMux(withSubject(t, uuid.NewString()))
	lastSeenID := "post-1"
	usecases.output = usecase.ClaimFeedOutput{
		Items:      []usecase.FeedCard{{PostID: "post-1", Position: 3, PostType: "BASIC"}},
		LastSeenID: &lastSeenID,
		HasMore:    true,
	}

	recorder := serve(mux, http.MethodGet, "/api/feed", "", nil)

	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q", got)
	}
	var body usecase.FeedPageResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(body.Items) != 1 || body.Items[0].Position != 3 || *body.LastSeenID != "post-1" || !body.HasMore {
		t.Fatalf("body = %s", recorder.Body)
	}
	if strings.Contains(recorder.Body.String(), "served_at") || strings.Contains(recorder.Body.String(), "ingested_at") {
		t.Fatalf("body exposes internal fields: %s", recorder.Body)
	}
}

// failingSigner fails every signature, as when no user delegation key can be fetched.
type failingSigner struct{}

func (failingSigner) SignReadURL(context.Context, string) (string, error) {
	return "", errors.New("user delegation key unavailable")
}

func TestClaimFeedHandler_AnswersASigningFailureWith500WithoutLeakingIt(t *testing.T) {
	usecases := &fakeUsecases{output: usecase.ClaimFeedOutput{
		Items: []usecase.FeedCard{{PostID: "post-1", Media: []usecase.FeedMedia{{ID: "m1", URL: "http://azurite:10000/devstoreaccount1/post-media-dev/m1", MediaType: "IMAGE", Order: 1}}}},
	}}
	mux := http.NewServeMux()
	presentation.RegisterRoutes(mux, withSubject(t, uuid.NewString()), presentation.Routes{
		Heartbeat:  presentation.NewHeartbeatHandler(fakeHeartbeat{usecases}, discardLogger()),
		Disconnect: presentation.NewDisconnectHandler(fakeDisconnect{usecases}, discardLogger()),
		ClaimFeed:  presentation.NewClaimFeedHandler(fakeClaim{usecases}, failingSigner{}, discardLogger()),
	})

	recorder := serve(mux, http.MethodGet, "/api/feed", "", nil)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", recorder.Code)
	}
	if body := recorder.Body.String(); strings.Contains(body, "delegation") || strings.Contains(body, "azurite") {
		t.Fatalf("body leaks the internal error or an unsigned url: %s", body)
	}
}
