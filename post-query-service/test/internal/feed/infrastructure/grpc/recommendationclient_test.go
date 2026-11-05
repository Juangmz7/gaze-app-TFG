package grpc_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/propagation"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/resolver"
	"google.golang.org/grpc/resolver/manual"
	"google.golang.org/grpc/status"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedgrpc "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc/recommendationv1"
)

// testTimeout only stops a test that would otherwise hang.
const testTimeout = 10 * time.Second

const (
	testPostID      = "6f1c3f4e-6f0e-4b52-9a57-0d3f6a3b1c11"
	testOtherPostID = "a2b5c7d9-1e34-4f60-8a21-7c9e0b4d2f33"
)

type attemptRecord struct {
	at       time.Time
	metadata metadata.MD
	request  *recommendationv1.GetRecommendedFeedRequest
}

// scriptedServer is a real gRPC server on localhost: its answer for each attempt comes
// from answer, whose argument is the attempt number starting at 1.
type scriptedServer struct {
	recommendationv1.UnimplementedRecommendationServiceServer

	address string
	answer  func(ctx context.Context, attempt int) ([]string, error)

	mu       sync.Mutex
	attempts []attemptRecord
}

func (s *scriptedServer) GetRecommendedFeed(ctx context.Context, request *recommendationv1.GetRecommendedFeedRequest) (*recommendationv1.GetRecommendedFeedResponse, error) {
	incoming, _ := metadata.FromIncomingContext(ctx)

	s.mu.Lock()
	s.attempts = append(s.attempts, attemptRecord{at: time.Now(), metadata: incoming, request: request})
	attempt := len(s.attempts)
	s.mu.Unlock()

	postIDs, err := s.answer(ctx, attempt)
	if err != nil {
		return nil, err
	}
	return &recommendationv1.GetRecommendedFeedResponse{PostIds: postIDs}, nil
}

func (s *scriptedServer) recorded() []attemptRecord {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.attempts)
}

func startServer(t *testing.T, answer func(ctx context.Context, attempt int) ([]string, error)) *scriptedServer {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen() error = %v", err)
	}
	server := &scriptedServer{address: listener.Addr().String(), answer: answer}
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

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// fastConfig keeps the waits short; the tests about the waits set their own.
func fastConfig(address string) feedgrpc.RecommendationClientConfig {
	return feedgrpc.RecommendationClientConfig{
		Address:         address,
		DialTimeout:     time.Second,
		PerRetryTimeout: 2 * time.Second,
		OverallTimeout:  testTimeout,
		MaxAttempts:     4,
		BackoffBase:     5 * time.Millisecond,
		BackoffMax:      20 * time.Millisecond,
		BackoffJitter:   0.2,
	}
}

func newClient(t *testing.T, config feedgrpc.RecommendationClientConfig, logger *slog.Logger) *feedgrpc.RecommendationClient {
	t.Helper()

	client, err := feedgrpc.NewRecommendationClient(config, logger)
	if err != nil {
		t.Fatalf("NewRecommendationClient() error = %v", err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Errorf("Close() error = %v", err)
		}
	})

	return client
}

func request() usecase.RecommendationRequest {
	return usecase.RecommendationRequest{UserID: uuid.New(), CorrelationID: "corr-refill-1"}
}

func failWith(code codes.Code) func(context.Context, int) ([]string, error) {
	return func(context.Context, int) ([]string, error) { return nil, status.Error(code, "scripted failure") }
}

func TestRecommendationClient_SucceedsOnTheFourthAttemptAfterThreeUnavailable(t *testing.T) {
	server := startServer(t, func(_ context.Context, attempt int) ([]string, error) {
		if attempt < 4 {
			return nil, status.Error(codes.Unavailable, "warming up")
		}
		return []string{testPostID}, nil
	})
	var logs bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logs, nil))
	client := newClient(t, fastConfig(server.address), logger)

	postIDs, err := client.GetRecommendedFeed(context.Background(), request())
	if err != nil {
		t.Fatalf("GetRecommendedFeed() error = %v", err)
	}

	if len(postIDs) != 1 || postIDs[0].String() != testPostID {
		t.Fatalf("postIDs = %v", postIDs)
	}
	if got := len(server.recorded()); got != 4 {
		t.Fatalf("attempts = %d, want 4", got)
	}
	if got := strings.Count(logs.String(), "recommendation grpc call retrying"); got != 3 {
		t.Fatalf("retry log lines = %d, want one per retry: %s", got, logs.String())
	}
	for _, want := range []string{"level=WARN", "correlation_id=corr-refill-1", "retry=3", "max_attempts=4", "code=Unavailable"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log misses %q: %s", want, logs.String())
		}
	}
}

// retry.WithMax counts attempts in go-grpc-middleware v2: this pins 3 retries, not 2.
func TestRecommendationClient_GivesUpAfterExactlyFourAttempts(t *testing.T) {
	server := startServer(t, failWith(codes.Unavailable))
	client := newClient(t, fastConfig(server.address), discardLogger())

	_, err := client.GetRecommendedFeed(context.Background(), request())

	if status.Code(errors.Unwrap(err)) != codes.Unavailable {
		t.Fatalf("GetRecommendedFeed() error = %v, want UNAVAILABLE", err)
	}
	if got := len(server.recorded()); got != 4 {
		t.Fatalf("attempts = %d, want 4", got)
	}
}

func TestRecommendationClient_RetriesOnlyTheCodesTheContractAllows(t *testing.T) {
	tests := []struct {
		code codes.Code
		want int
	}{
		{codes.Unavailable, 4},
		{codes.ResourceExhausted, 4},
		{codes.DeadlineExceeded, 4},
		{codes.InvalidArgument, 1},
		{codes.NotFound, 1},
		{codes.FailedPrecondition, 1},
		{codes.PermissionDenied, 1},
		{codes.Internal, 1},
	}

	for _, test := range tests {
		t.Run(test.code.String(), func(t *testing.T) {
			server := startServer(t, failWith(test.code))
			client := newClient(t, fastConfig(server.address), discardLogger())

			_, err := client.GetRecommendedFeed(context.Background(), request())

			if status.Code(errors.Unwrap(err)) != test.code {
				t.Fatalf("GetRecommendedFeed() error = %v, want %s", err, test.code)
			}
			if got := len(server.recorded()); got != test.want {
				t.Fatalf("attempts = %d, want %d", got, test.want)
			}
		})
	}
}

func waits(attempts []attemptRecord) []time.Duration {
	gaps := make([]time.Duration, 0, len(attempts))
	for index := 1; index < len(attempts); index++ {
		gaps = append(gaps, attempts[index].at.Sub(attempts[index-1].at))
	}
	return gaps
}

// The real settings: the jitter ranges do not overlap, so the order is deterministic.
func TestRecommendationClient_WaitsLongerBeforeEachRetryAndAtMostOneSecond(t *testing.T) {
	server := startServer(t, failWith(codes.Unavailable))
	config := fastConfig(server.address)
	config.BackoffBase = 250 * time.Millisecond
	config.BackoffMax = time.Second
	client := newClient(t, config, discardLogger())

	if _, err := client.GetRecommendedFeed(context.Background(), request()); err == nil {
		t.Fatal("GetRecommendedFeed() error = nil, want UNAVAILABLE")
	}

	gaps := waits(server.recorded())
	if len(gaps) != 3 {
		t.Fatalf("waits = %v, want 3", gaps)
	}
	if !(gaps[0] < gaps[1] && gaps[1] < gaps[2]) {
		t.Fatalf("waits = %v, want them to increase", gaps)
	}
	// The cap is on the wait; the rest of the bound is the round trip of a loaded machine.
	if gaps[2] > time.Second+300*time.Millisecond {
		t.Fatalf("last wait = %s, want at most about 1s", gaps[2])
	}
}

func TestRecommendationClient_CapsTheBackoff(t *testing.T) {
	server := startServer(t, failWith(codes.Unavailable))
	config := fastConfig(server.address)
	config.BackoffBase = 100 * time.Millisecond
	config.BackoffMax = 120 * time.Millisecond
	client := newClient(t, config, discardLogger())

	if _, err := client.GetRecommendedFeed(context.Background(), request()); err == nil {
		t.Fatal("GetRecommendedFeed() error = nil, want UNAVAILABLE")
	}

	// Uncapped, the last wait would be about 400 ms.
	gaps := waits(server.recorded())
	if gaps[2] >= 300*time.Millisecond {
		t.Fatalf("waits = %v, want the last one capped near 120ms", gaps)
	}
}

func TestRecommendationClient_RetriesAnAttemptThatExceedsThePerRetryTimeout(t *testing.T) {
	server := startServer(t, func(ctx context.Context, attempt int) ([]string, error) {
		if attempt == 1 {
			<-ctx.Done()
			return nil, ctx.Err()
		}
		return []string{testPostID}, nil
	})
	config := fastConfig(server.address)
	config.PerRetryTimeout = 150 * time.Millisecond
	client := newClient(t, config, discardLogger())

	postIDs, err := client.GetRecommendedFeed(context.Background(), request())
	if err != nil {
		t.Fatalf("GetRecommendedFeed() error = %v", err)
	}

	if len(postIDs) != 1 || len(server.recorded()) != 2 {
		t.Fatalf("postIDs = %v, attempts = %d, want the second attempt to answer", postIDs, len(server.recorded()))
	}
}

func TestRecommendationClient_TheOverallDeadlineStopsTheRetries(t *testing.T) {
	server := startServer(t, func(ctx context.Context, _ int) ([]string, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	})
	config := fastConfig(server.address)
	config.OverallTimeout = 200 * time.Millisecond
	client := newClient(t, config, discardLogger())

	started := time.Now()
	_, err := client.GetRecommendedFeed(context.Background(), request())

	if status.Code(errors.Unwrap(err)) != codes.DeadlineExceeded {
		t.Fatalf("GetRecommendedFeed() error = %v, want DEADLINE_EXCEEDED", err)
	}
	if elapsed := time.Since(started); elapsed > config.PerRetryTimeout {
		t.Fatalf("took %s, want the overall deadline of %s to stop it", elapsed, config.OverallTimeout)
	}
	if got := len(server.recorded()); got != 1 {
		t.Fatalf("attempts = %d, want no retry past the deadline", got)
	}
}

func TestRecommendationClient_CancellationReachesTheServerAndStopsTheRetries(t *testing.T) {
	started := make(chan struct{})
	cancelled := make(chan struct{})
	server := startServer(t, func(ctx context.Context, _ int) ([]string, error) {
		close(started)
		<-ctx.Done()
		close(cancelled)
		return nil, ctx.Err()
	})
	client := newClient(t, fastConfig(server.address), discardLogger())

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := client.GetRecommendedFeed(ctx, request())
		result <- err
	}()

	<-started
	cancel()

	select {
	case <-cancelled:
	case <-time.After(testTimeout):
		t.Fatal("the cancellation did not reach the server")
	}
	select {
	case err := <-result:
		if status.Code(errors.Unwrap(err)) != codes.Canceled {
			t.Fatalf("GetRecommendedFeed() error = %v, want CANCELED", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("the call did not return after the cancellation")
	}
	if got := len(server.recorded()); got != 1 {
		t.Fatalf("attempts = %d, want no retry after the cancellation", got)
	}
}

func TestRecommendationClient_SendsTheContractOnEveryAttempt(t *testing.T) {
	server := startServer(t, func(_ context.Context, attempt int) ([]string, error) {
		if attempt == 1 {
			return nil, status.Error(codes.Unavailable, "warming up")
		}
		return nil, nil
	})
	client := newClient(t, fastConfig(server.address), discardLogger())

	const traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	const tracestate = "vendor=value"
	ctx := propagation.TraceContext{}.Extract(context.Background(), propagation.MapCarrier{"traceparent": traceparent, "tracestate": tracestate})
	userID := uuid.New()
	excluded := []uuid.UUID{uuid.MustParse(testPostID), uuid.MustParse(testOtherPostID)}

	if _, err := client.GetRecommendedFeed(ctx, usecase.RecommendationRequest{UserID: userID, ExcludedPostIDs: excluded, CorrelationID: "corr-refill-1"}); err != nil {
		t.Fatalf("GetRecommendedFeed() error = %v", err)
	}

	attempts := server.recorded()
	if len(attempts) != 2 {
		t.Fatalf("attempts = %d, want 2", len(attempts))
	}
	for index, attempt := range attempts {
		for key, want := range map[string]string{"x-correlation-id": "corr-refill-1", "traceparent": traceparent, "tracestate": tracestate} {
			if got := attempt.metadata.Get(key); len(got) != 1 || got[0] != want {
				t.Fatalf("attempt %d metadata %s = %v, want %q", index+1, key, got, want)
			}
		}
		if attempt.request.GetUserId() != userID.String() {
			t.Fatalf("user id = %q", attempt.request.GetUserId())
		}
		if attempt.request.GetReason() != recommendationv1.FeedRequestReason_FEED_EMPTY {
			t.Fatalf("reason = %s, want FEED_EMPTY", attempt.request.GetReason())
		}
		if !slices.Equal(attempt.request.GetExcludedPostIds(), []string{testPostID, testOtherPostID}) {
			t.Fatalf("excluded = %v", attempt.request.GetExcludedPostIds())
		}
	}
}

func TestRecommendationClient_LeavesOutACorrelationIDTheContractDoesNotAllow(t *testing.T) {
	for name, correlationID := range map[string]string{"empty": "", "with a space": "corr refill", "not ascii": "corr-é", "too long": strings.Repeat("c", 129)} {
		t.Run(name, func(t *testing.T) {
			server := startServer(t, func(context.Context, int) ([]string, error) { return nil, nil })
			client := newClient(t, fastConfig(server.address), discardLogger())

			if _, err := client.GetRecommendedFeed(context.Background(), usecase.RecommendationRequest{UserID: uuid.New(), CorrelationID: correlationID}); err != nil {
				t.Fatalf("GetRecommendedFeed() error = %v, a bad id must not fail the call", err)
			}
			if got := server.recorded()[0].metadata.Get(feedgrpc.CorrelationIDMetadataKey); len(got) != 0 {
				t.Fatalf("metadata = %v, want it left out", got)
			}
		})
	}
}

func TestRecommendationClient_ReadsTheAnswer(t *testing.T) {
	t.Run("no candidates is an empty list and no error", func(t *testing.T) {
		server := startServer(t, func(context.Context, int) ([]string, error) { return nil, nil })
		client := newClient(t, fastConfig(server.address), discardLogger())

		postIDs, err := client.GetRecommendedFeed(context.Background(), request())
		if err != nil || len(postIDs) != 0 {
			t.Fatalf("GetRecommendedFeed() = %v, %v, want no posts and no error", postIDs, err)
		}
	})

	t.Run("an id that is not a UUID is discarded with a warning, keeping the order", func(t *testing.T) {
		server := startServer(t, func(context.Context, int) ([]string, error) {
			return []string{testOtherPostID, "not-a-uuid", testPostID}, nil
		})
		var logs bytes.Buffer
		client := newClient(t, fastConfig(server.address), slog.New(slog.NewTextHandler(&logs, nil)))

		postIDs, err := client.GetRecommendedFeed(context.Background(), request())
		if err != nil {
			t.Fatalf("GetRecommendedFeed() error = %v", err)
		}

		if len(postIDs) != 2 || postIDs[0].String() != testOtherPostID || postIDs[1].String() != testPostID {
			t.Fatalf("postIDs = %v", postIDs)
		}
		if !strings.Contains(logs.String(), "level=WARN") || !strings.Contains(logs.String(), "correlation_id=corr-refill-1") {
			t.Fatalf("log = %s", logs.String())
		}
	})

	t.Run("only unusable ids is a failure, not the end of the feed", func(t *testing.T) {
		server := startServer(t, func(context.Context, int) ([]string, error) { return []string{"not-a-uuid"}, nil })
		client := newClient(t, fastConfig(server.address), discardLogger())

		if _, err := client.GetRecommendedFeed(context.Background(), request()); err == nil {
			t.Fatal("GetRecommendedFeed() error = nil, want a contract failure")
		}
	})
}

func TestRecommendationClient_RejectsAMissingUserID(t *testing.T) {
	server := startServer(t, func(context.Context, int) ([]string, error) { return nil, nil })
	client := newClient(t, fastConfig(server.address), discardLogger())

	if _, err := client.GetRecommendedFeed(context.Background(), usecase.RecommendationRequest{}); err == nil {
		t.Fatal("GetRecommendedFeed() error = nil, want an error")
	}
	if got := len(server.recorded()); got != 0 {
		t.Fatalf("attempts = %d, want none", got)
	}
}

func TestNewRecommendationClient_RejectsAConfigThatMakesNoSense(t *testing.T) {
	tests := map[string]func(*feedgrpc.RecommendationClientConfig){
		"blank address":        func(c *feedgrpc.RecommendationClientConfig) { c.Address = " " },
		"no attempts":          func(c *feedgrpc.RecommendationClientConfig) { c.MaxAttempts = 0 },
		"no dial timeout":      func(c *feedgrpc.RecommendationClientConfig) { c.DialTimeout = 0 },
		"no per retry timeout": func(c *feedgrpc.RecommendationClientConfig) { c.PerRetryTimeout = 0 },
		"no overall timeout":   func(c *feedgrpc.RecommendationClientConfig) { c.OverallTimeout = 0 },
		"no backoff base":      func(c *feedgrpc.RecommendationClientConfig) { c.BackoffBase = 0 },
		"no backoff cap":       func(c *feedgrpc.RecommendationClientConfig) { c.BackoffMax = 0 },
	}
	for name, breakIt := range tests {
		t.Run(name, func(t *testing.T) {
			config := fastConfig("dns:///recommendation-service:50051")
			breakIt(&config)
			if _, err := feedgrpc.NewRecommendationClient(config, discardLogger()); err == nil {
				t.Fatal("NewRecommendationClient() error = nil, want an error")
			}
		})
	}
}

func TestRecommendationClient_AnswersThroughADNSTarget(t *testing.T) {
	server := startServer(t, func(context.Context, int) ([]string, error) { return []string{testPostID}, nil })
	_, port, err := net.SplitHostPort(server.address)
	if err != nil {
		t.Fatalf("SplitHostPort() error = %v", err)
	}
	client := newClient(t, fastConfig("dns:///localhost:"+port), discardLogger())

	postIDs, err := client.GetRecommendedFeed(context.Background(), request())
	if err != nil || len(postIDs) != 1 {
		t.Fatalf("GetRecommendedFeed() = %v, %v", postIDs, err)
	}
}

// Two backends behind one resolved name, like the pods of the headless Service.
func TestRecommendationClient_BalancesEveryCallAcrossTheResolvedBackends(t *testing.T) {
	answer := func(context.Context, int) ([]string, error) { return []string{testPostID}, nil }
	first, second := startServer(t, answer), startServer(t, answer)

	backends := manual.NewBuilderWithScheme("roundrobintest")
	backends.InitialState(resolver.State{Addresses: []resolver.Address{{Addr: first.address}, {Addr: second.address}}})
	resolver.Register(backends)
	client := newClient(t, fastConfig("roundrobintest:///recommendation-service"), discardLogger())

	// Round robin only picks from what is connected, so the first calls may land on one.
	deadline := time.Now().Add(testTimeout)
	for len(first.recorded()) == 0 || len(second.recorded()) == 0 {
		if time.Now().After(deadline) {
			t.Fatalf("calls = %d and %d, want both backends to answer", len(first.recorded()), len(second.recorded()))
		}
		if _, err := client.GetRecommendedFeed(context.Background(), request()); err != nil {
			t.Fatalf("GetRecommendedFeed() error = %v", err)
		}
	}
}
