package presentation_test

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedgrpc "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc/recommendationv1"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
)

// refillRetryAfter is what the degraded responses of these tests must announce.
const refillRetryAfter = 3 * time.Second

// recommendationServer is a real gRPC server on localhost answering what the test
// scripts, the argument being the attempt number starting at 1.
type recommendationServer struct {
	recommendationv1.UnimplementedRecommendationServiceServer

	address string
	answer  func(ctx context.Context, attempt int) ([]string, error)

	mu       sync.Mutex
	requests []*recommendationv1.GetRecommendedFeedRequest
	metadata []metadata.MD
}

func (s *recommendationServer) GetRecommendedFeed(ctx context.Context, request *recommendationv1.GetRecommendedFeedRequest) (*recommendationv1.GetRecommendedFeedResponse, error) {
	incoming, _ := metadata.FromIncomingContext(ctx)
	s.mu.Lock()
	s.requests = append(s.requests, request)
	s.metadata = append(s.metadata, incoming)
	attempt := len(s.requests)
	s.mu.Unlock()

	postIDs, err := s.answer(ctx, attempt)
	if err != nil {
		return nil, err
	}
	return &recommendationv1.GetRecommendedFeedResponse{PostIds: postIDs}, nil
}

func (s *recommendationServer) attempts() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.requests)
}

func (s *recommendationServer) request(index int) *recommendationv1.GetRecommendedFeedRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requests[index]
}

func (s *recommendationServer) metadataOf(index int) metadata.MD {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.metadata[index]
}

func startRecommendationServer(t *testing.T, answer func(ctx context.Context, attempt int) ([]string, error)) *recommendationServer {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen() error = %v", err)
	}
	server := &recommendationServer{address: listener.Addr().String(), answer: answer}
	grpcServer := googlegrpc.NewServer()
	recommendationv1.RegisterRecommendationServiceServer(grpcServer, server)
	served := make(chan error, 1)
	go func() { served <- grpcServer.Serve(listener) }()
	t.Cleanup(func() {
		grpcServer.Stop()
		if err := <-served; err != nil && !errors.Is(err, googlegrpc.ErrServerStopped) {
			t.Errorf("Serve() error = %v", err)
		}
	})

	return server
}

// messageSignal is a logger handler that closes seen at the first record with message,
// so a test can act exactly when the code reaches that point.
type messageSignal struct {
	message string
	once    *sync.Once
	seen    chan struct{}
}

func (s messageSignal) Enabled(context.Context, slog.Level) bool { return true }

func (s messageSignal) Handle(_ context.Context, record slog.Record) error {
	if record.Message == s.message {
		s.once.Do(func() { close(s.seen) })
	}
	return nil
}

func (s messageSignal) WithAttrs([]slog.Attr) slog.Handler { return s }

func (s messageSignal) WithGroup(string) slog.Handler { return s }

// countingPrefetch records low watermark prefetches.
type countingPrefetch struct {
	mu     sync.Mutex
	inputs []usecase.RequestFeedPrefetchInput
}

func (p *countingPrefetch) Execute(_ context.Context, input usecase.RequestFeedPrefetchInput) (usecase.RequestFeedPrefetchOutput, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.inputs = append(p.inputs, input)
	return usecase.RequestFeedPrefetchOutput{Status: usecase.RequestFeedPrefetchPublished}, nil
}

func (p *countingPrefetch) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.inputs)
}

// refillApp is the real stack behind GET /api/feed with the refill wired: real
// MongoDB, real lease, real gRPC client and a real gRPC server.
type refillApp struct {
	*feedApp
	server       *recommendationServer
	lease        *usecase.FeedLeaseManager
	prefetch     *countingPrefetch
	lowWatermark *usecase.RequestLowWatermarkPrefetchUsecase
	// leaseDenied is closed when a claim finds the lease held.
	leaseDenied chan struct{}
}

func newRefillApp(t *testing.T, ctx context.Context, answer func(ctx context.Context, attempt int) ([]string, error)) *refillApp {
	t.Helper()

	app := newFeedApp(t, ctx)
	server := startRecommendationServer(t, answer)
	logger := discardLogger()

	client, err := feedgrpc.NewRecommendationClient(feedgrpc.RecommendationClientConfig{
		Address:         "dns:///localhost:" + portOf(t, server.address),
		DialTimeout:     time.Second,
		PerRetryTimeout: 5 * time.Second,
		OverallTimeout:  20 * time.Second,
		MaxAttempts:     4,
		BackoffBase:     5 * time.Millisecond,
		BackoffMax:      20 * time.Millisecond,
		BackoffJitter:   0.2,
	}, logger)
	if err != nil {
		t.Fatalf("NewRecommendationClient() error = %v", err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Errorf("Close() error = %v", err)
		}
	})

	sessions := feedmongo.NewSessionRepository(app.db)
	units := feedmongo.NewFeedUnitRepository(app.db)
	hydration := feedmongo.NewHydrationRepository(app.db)
	leaseDenied := make(chan struct{})
	leaseLogger := slog.New(messageSignal{message: "feed lease denied", once: &sync.Once{}, seen: leaseDenied})
	lease := usecase.NewFeedLeaseManager(sessions, usecase.FeedLeaseConfig{Owner: "replica-1", TTL: time.Minute, ReleaseTimeout: 5 * time.Second}, leaseLogger)
	backlog := usecase.NewFeedBacklog(units, usecase.FeedBacklogConfig{MaxUnserved: 60, ExcludedIDsCap: 200}, logger)
	prefetch := &countingPrefetch{}
	lowWatermark := usecase.NewRequestLowWatermarkPrefetch(backlog, prefetch, usecase.RequestLowWatermarkPrefetchConfig{PageSize: 15, Concurrency: 4, Timeout: 5 * time.Second}, logger)
	t.Cleanup(lowWatermark.Wait)
	refill := usecase.NewRefillFeed(units, lease, backlog, client, usecase.NewBuildFeedUnits(hydration, sessions, units, logger), usecase.RefillFeedConfig{
		LeaseWait:    time.Second,
		PollInterval: 10 * time.Millisecond,
		RetryAfter:   refillRetryAfter,
	}, logger)
	claim := usecase.NewClaimFeed(units, hydration, usecase.ClaimFeedConfig{DefaultPageSize: 15, MaxPageSize: 20, MaxReads: 3}, logger).WithLowWatermark(lowWatermark).WithRefill(refill)

	app.mux = http.NewServeMux()
	presentation.RegisterRoutes(app.mux, withSubject(t, app.viewerID.String()), presentation.Routes{
		Heartbeat:  presentation.NewHeartbeatHandler(usecase.NewRegisterHeartbeat(sessions, logger), logger),
		Disconnect: presentation.NewDisconnectHandler(usecase.NewRegisterUserDisconnected(sessions, noPrefetch{}, 5*time.Second, logger), logger),
		ClaimFeed:  presentation.NewClaimFeedHandler(claim, logger),
	})

	return &refillApp{feedApp: app, server: server, lease: lease, prefetch: prefetch, lowWatermark: lowWatermark, leaseDenied: leaseDenied}
}

func portOf(t *testing.T, address string) string {
	t.Helper()
	_, port, err := net.SplitHostPort(address)
	if err != nil {
		t.Fatalf("SplitHostPort() error = %v", err)
	}
	return port
}

// projectPosts writes count posts to the read models, without feed units.
func (a *refillApp) projectPosts(t *testing.T, ctx context.Context, count int) []string {
	t.Helper()

	// seedPosts is the way to project them, and the units it builds are for someone else.
	realViewer := a.viewerID
	a.viewerID = uuid.New()
	postIDs := a.seedPosts(t, ctx, count)
	a.viewerID = realViewer
	return postIDs
}

// leaseToken is the token holding the viewer's lease, or "" when it is free.
func (a *refillApp) leaseToken(t *testing.T, ctx context.Context) string {
	t.Helper()

	var raw bson.M
	err := a.db.Collection("user_feed_sessions").FindOne(ctx, bson.M{"user_id": a.viewerID.String()}).Decode(&raw)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	token, found := raw["prefetch_lease_token"]
	if !found || token == nil {
		return ""
	}
	if text, isText := token.(string); isText {
		return text
	}
	return "held"
}

func (a *refillApp) storedPositions(t *testing.T, ctx context.Context) []int64 {
	t.Helper()

	cursor, err := a.db.Collection("feed_units").Find(ctx, bson.M{"user_id": a.viewerID.String()})
	if err != nil {
		t.Fatalf("Find() error = %v", err)
	}
	var units []struct {
		Position int64 `bson:"position"`
	}
	if err := cursor.All(ctx, &units); err != nil {
		t.Fatalf("All() error = %v", err)
	}
	positions := make([]int64, 0, len(units))
	for _, unit := range units {
		positions = append(positions, unit.Position)
	}
	slices.Sort(positions)
	return positions
}

func assertDegradedResponse(t *testing.T, recorder *httptest.ResponseRecorder) {
	t.Helper()

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503: %s", recorder.Code, recorder.Body)
	}
	if got := recorder.Header().Get("Retry-After"); got != "3" {
		t.Fatalf("Retry-After = %q, want 3", got)
	}
	var body common.ApiErrorResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if body.Code != "feed_temporarily_unavailable" {
		t.Fatalf("code = %q", body.Code)
	}
}

func refillContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	t.Cleanup(cancel)
	return ctx
}

func TestFeedRefill_AnExhaustedFeedFallsBackToGRPCPersistsAndServesInTheSameResponse(t *testing.T) {
	ctx := refillContext(t)
	var recommended []string
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return recommended, nil })
	seeded := app.seedPosts(t, ctx, 2)
	recommended = app.projectPosts(t, ctx, 20)

	served := app.claim(t, "")
	if !slices.Equal(served.postIDs(), seeded) {
		t.Fatalf("first page = %v, want the stored units", served.postIDs())
	}
	if app.server.attempts() != 0 {
		t.Fatal("no gRPC call while units remain")
	}

	refilled := app.claim(t, "?lastSeenId="+*served.LastSeenID)

	if !slices.Equal(refilled.postIDs(), recommended[:15]) || !refilled.HasMore {
		t.Fatalf("refilled page = %+v, want the first page of the recommended ids", refilled)
	}
	if first, last := served.Items[1].Position, refilled.Items[0].Position; last <= first {
		t.Fatalf("positions %d then %d, want the sequence to continue", first, last)
	}
	if got := app.storedPositions(t, ctx); len(got) != 22 {
		t.Fatalf("stored units = %d, want 22", len(got))
	}
	request := app.server.request(0)
	if request.GetUserId() != app.viewerID.String() || request.GetReason() != recommendationv1.FeedRequestReason_FEED_EMPTY {
		t.Fatalf("request = %v", request)
	}
	if !slices.Equal(sorted(request.GetExcludedPostIds()), sorted(seeded)) {
		t.Fatalf("excluded = %v, want the served ids %v", request.GetExcludedPostIds(), seeded)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}

	// The rest of the batch comes from the store.
	next := app.claim(t, "?lastSeenId="+*refilled.LastSeenID)
	if !slices.Equal(next.postIDs(), recommended[15:]) || app.server.attempts() != 1 {
		t.Fatalf("next page = %v, attempts = %d", next.postIDs(), app.server.attempts())
	}
}

func sorted(values []string) []string {
	clone := slices.Clone(values)
	slices.Sort(clone)
	return clone
}

func TestFeedRefill_SucceedsOnTheFourthAttemptAgainstAnUnavailableServer(t *testing.T) {
	ctx := refillContext(t)
	var recommended []string
	app := newRefillApp(t, ctx, func(_ context.Context, attempt int) ([]string, error) {
		if attempt < 4 {
			return nil, status.Error(codes.Unavailable, "warming up")
		}
		return recommended, nil
	})
	recommended = app.projectPosts(t, ctx, 2)

	got := app.claim(t, "")

	if !slices.Equal(got.postIDs(), recommended) || app.server.attempts() != 4 {
		t.Fatalf("page = %v, attempts = %d, want the fourth attempt to serve", got.postIDs(), app.server.attempts())
	}
}

func TestFeedRefill_AnOutageAnswers503ReleasesTheLeaseAndTheNextClaimRecovers(t *testing.T) {
	ctx := refillContext(t)
	var recommended []string
	var healthy bool
	var mu sync.Mutex
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) {
		mu.Lock()
		defer mu.Unlock()
		if !healthy {
			return nil, status.Error(codes.Unavailable, "outage")
		}
		return recommended, nil
	})
	recommended = app.projectPosts(t, ctx, 2)

	assertDegradedResponse(t, serve(app.mux, http.MethodGet, "/api/feed", "", nil))
	if got := app.server.attempts(); got != 4 {
		t.Fatalf("attempts = %d, want 4", got)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}
	if got := app.prefetch.count(); got != 0 {
		t.Fatalf("prefetches = %d, want none after a failed claim", got)
	}

	mu.Lock()
	healthy = true
	mu.Unlock()
	if got := app.claim(t, ""); !slices.Equal(got.postIDs(), recommended) {
		t.Fatalf("page after the outage = %v", got.postIDs())
	}
}

func TestFeedRefill_InvalidArgumentIsNotRetriedAndIsDegraded(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) {
		return nil, status.Error(codes.InvalidArgument, "user_id and excluded_post_ids must be UUIDs")
	})

	assertDegradedResponse(t, serve(app.mux, http.MethodGet, "/api/feed", "", nil))

	if got := app.server.attempts(); got != 1 {
		t.Fatalf("attempts = %d, want no retry", got)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}
}

func TestFeedRefill_NoPostsIsAnEmptyPageWithoutMoreAndCausesNoPrefetch(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return nil, nil })

	got := app.claim(t, "")

	if len(got.Items) != 0 || got.HasMore || got.LastSeenID != nil {
		t.Fatalf("page = %+v, want an empty page with hasMore=false", got)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}
	app.lowWatermark.Wait()
	if count := app.prefetch.count(); count != 0 {
		t.Fatalf("prefetches = %d, want none: there is nothing to prefetch", count)
	}
}

func TestFeedRefill_PostsNotProjectedYetAreDegradedAndNotTheEndOfTheFeed(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return []string{uuid.NewString()}, nil })

	assertDegradedResponse(t, serve(app.mux, http.MethodGet, "/api/feed", "", nil))

	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}
}

func TestFeedRefill_TheLowWatermarkFiresAfterARefillThatLeftLittle(t *testing.T) {
	ctx := refillContext(t)
	var recommended []string
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return recommended, nil })
	recommended = app.projectPosts(t, ctx, 3)

	if got := app.claim(t, ""); !slices.Equal(got.postIDs(), recommended) {
		t.Fatalf("page = %v", got.postIDs())
	}

	app.lowWatermark.Wait()
	if count := app.prefetch.count(); count != 1 {
		t.Fatalf("prefetches = %d, want one, with the lease already free", count)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released", token)
	}
}

func TestFeedRefill_ABatchLeavingAFullPageCausesNoPrefetch(t *testing.T) {
	ctx := refillContext(t)
	var recommended []string
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return recommended, nil })
	recommended = app.projectPosts(t, ctx, 30)

	if got := app.claim(t, ""); len(got.Items) != 15 {
		t.Fatalf("items = %d, want 15", len(got.Items))
	}

	app.lowWatermark.Wait()
	if count := app.prefetch.count(); count != 0 {
		t.Fatalf("prefetches = %d, want none: 15 remain unserved", count)
	}
}

func TestFeedRefill_WithTheLeaseHeldServesWhatTheBuildInFlightStores(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return nil, nil })
	held, acquired, err := app.lease.AcquireForRequest(ctx, app.viewerID, "corr-async")
	if err != nil || !acquired {
		t.Fatalf("AcquireForRequest() = %v, %v", acquired, err)
	}

	// The units land only once the claim has found the lease held, inside its wait.
	result := make(chan *httptest.ResponseRecorder, 1)
	go func() { result <- serve(app.mux, http.MethodGet, "/api/feed", "", nil) }()
	select {
	case <-app.leaseDenied:
	case <-ctx.Done():
		t.Fatal("the claim did not reach the lease")
	}
	stored := app.seedPosts(t, ctx, 2)

	recorder := <-result
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
	}
	var got page
	if err := json.Unmarshal(recorder.Body.Bytes(), &got); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}

	if !slices.Equal(got.postIDs(), stored) {
		t.Fatalf("page = %v, want %v", got.postIDs(), stored)
	}
	if attempts := app.server.attempts(); attempts != 0 {
		t.Fatalf("gRPC attempts = %d, want none while the lease is held", attempts)
	}
	if released, err := app.lease.Release(ctx, app.viewerID, held.Token, "corr-async"); err != nil || !released {
		t.Fatalf("Release() = %v, %v, want the holder's lease untouched", released, err)
	}
}

func TestFeedRefill_WithTheLeaseHeldAnswers503WhenTheWaitEnds(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return nil, nil })
	held, acquired, err := app.lease.AcquireForRequest(ctx, app.viewerID, "corr-async")
	if err != nil || !acquired {
		t.Fatalf("AcquireForRequest() = %v, %v", acquired, err)
	}

	assertDegradedResponse(t, serve(app.mux, http.MethodGet, "/api/feed", "", nil))

	if attempts := app.server.attempts(); attempts != 0 {
		t.Fatalf("gRPC attempts = %d, want none while the lease is held", attempts)
	}
	if released, err := app.lease.Release(ctx, app.viewerID, held.Token, "corr-async"); err != nil || !released {
		t.Fatalf("Release() = %v, %v, want the holder's lease untouched", released, err)
	}
}

func TestFeedRefill_ACancelledRequestReachesGRPCAbortsAndReleasesTheLease(t *testing.T) {
	ctx := refillContext(t)
	inCall := make(chan struct{})
	cancelled := make(chan struct{})
	app := newRefillApp(t, ctx, func(call context.Context, _ int) ([]string, error) {
		close(inCall)
		<-call.Done()
		close(cancelled)
		return nil, call.Err()
	})

	requestCtx, cancelRequest := context.WithCancel(ctx)
	defer cancelRequest()
	request := httptest.NewRequest(http.MethodGet, "/api/feed", nil).WithContext(requestCtx)
	recorder := httptest.NewRecorder()
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		app.mux.ServeHTTP(recorder, request)
	}()

	<-inCall
	cancelRequest()

	for name, signal := range map[string]<-chan struct{}{"the server": cancelled, "the handler": finished} {
		select {
		case <-signal:
		case <-ctx.Done():
			t.Fatalf("%s did not see the cancellation", name)
		}
	}
	if recorder.Code != 499 {
		t.Fatalf("status = %d, want 499: %s", recorder.Code, recorder.Body)
	}
	if attempts := app.server.attempts(); attempts != 1 {
		t.Fatalf("attempts = %d, want no retry", attempts)
	}
	if token := app.leaseToken(t, ctx); token != "" {
		t.Fatalf("lease token = %q, want it released despite the cancellation", token)
	}
}

func TestFeedRefill_ACancelledRequestReachesMongo(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return nil, nil })

	requestCtx, cancelRequest := context.WithCancel(ctx)
	cancelRequest()
	recorder := httptest.NewRecorder()
	app.mux.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/feed", nil).WithContext(requestCtx))

	if recorder.Code != 499 {
		t.Fatalf("status = %d, want 499: %s", recorder.Code, recorder.Body)
	}
	if attempts := app.server.attempts(); attempts != 0 {
		t.Fatalf("attempts = %d, want the first read to stop the claim", attempts)
	}
}

func TestFeedRefill_CarriesTheCorrelationIDAndTheTraceToTheServer(t *testing.T) {
	ctx := refillContext(t)
	app := newRefillApp(t, ctx, func(context.Context, int) ([]string, error) { return nil, nil })

	const traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	recorder := serve(app.mux, http.MethodGet, "/api/feed", "", map[string]string{
		presentation.CorrelationIDHeader: "corr-from-the-client",
		"traceparent":                    traceparent,
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}

	got := app.server.metadataOf(0)
	if ids := got.Get(feedgrpc.CorrelationIDMetadataKey); len(ids) != 1 || ids[0] != "corr-from-the-client" {
		t.Fatalf("x-correlation-id = %v", ids)
	}
	if traces := got.Get("traceparent"); len(traces) != 1 || traces[0] != traceparent {
		t.Fatalf("traceparent = %v", traces)
	}
}
