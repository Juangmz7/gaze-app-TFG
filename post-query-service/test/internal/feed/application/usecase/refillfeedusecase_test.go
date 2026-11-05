package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

const refillTestTimeout = 10 * time.Second

type fakeRecommendationClient struct {
	postIDs  []uuid.UUID
	err      error
	requests []usecase.RecommendationRequest
	ctxs     []context.Context
	during   func()
}

func (f *fakeRecommendationClient) GetRecommendedFeed(ctx context.Context, request usecase.RecommendationRequest) ([]uuid.UUID, error) {
	f.requests = append(f.requests, request)
	f.ctxs = append(f.ctxs, ctx)
	if f.during != nil {
		f.during()
	}
	return f.postIDs, f.err
}

type fakeRefillUnits struct {
	found     atomic.Bool
	foundAt   int64
	reads     atomic.Int64
	err       error
	gotAfter  *int64
	gotUnread bool
}

func (f *fakeRefillUnits) HasAfterPosition(_ context.Context, _ string, afterPosition *int64, unservedOnly bool) (bool, error) {
	reads := f.reads.Add(1)
	f.gotAfter, f.gotUnread = afterPosition, unservedOnly
	if f.err != nil {
		return false, f.err
	}
	return f.found.Load() || (f.foundAt > 0 && reads >= f.foundAt), nil
}

type fakeRefillBuilder struct {
	output usecase.BuildFeedUnitsOutput
	err    error
	inputs []usecase.BuildFeedUnitsInput
}

func (f *fakeRefillBuilder) Execute(_ context.Context, input usecase.BuildFeedUnitsInput) (usecase.BuildFeedUnitsOutput, error) {
	f.inputs = append(f.inputs, input)
	return f.output, f.err
}

type fakeRefillBacklog struct {
	unserved []string
	served   []string
	err      error
}

func (f *fakeRefillBacklog) CountUnserved(context.Context, string, int64) (int64, error) {
	return int64(len(f.unserved)), nil
}

func (f *fakeRefillBacklog) FindAfterPosition(_ context.Context, _ string, _ *int64, _ bool, _ int) ([]usecase.FeedUnitRef, error) {
	refs := make([]usecase.FeedUnitRef, 0, len(f.unserved))
	for _, postID := range f.unserved {
		refs = append(refs, usecase.FeedUnitRef{PostID: postID})
	}
	return refs, f.err
}

func (f *fakeRefillBacklog) FindRecentlyServedPostIDs(context.Context, string, int) ([]string, error) {
	return f.served, nil
}

type refillFixture struct {
	units   *fakeRefillUnits
	lease   *fakeFeedLeaseRepository
	backlog *fakeRefillBacklog
	client  *fakeRecommendationClient
	builder *fakeRefillBuilder
	logs    *bytes.Buffer
	usecase *usecase.RefillFeedUsecase
	input   usecase.RefillFeedInput
}

func newRefillFixture() *refillFixture {
	f := &refillFixture{
		units:   &fakeRefillUnits{},
		lease:   &fakeFeedLeaseRepository{released: true},
		backlog: &fakeRefillBacklog{},
		client:  &fakeRecommendationClient{postIDs: []uuid.UUID{uuid.New(), uuid.New()}},
		builder: &fakeRefillBuilder{output: usecase.BuildFeedUnitsOutput{Inserted: 2}},
		input:   usecase.RefillFeedInput{UserID: uuid.New(), CorrelationID: "corr-1"},
	}
	logger, logs := bufferLogger()
	f.logs = logs
	manager := usecase.NewFeedLeaseManager(f.lease, testLeaseConfig, logger)
	backlog := usecase.NewFeedBacklog(f.backlog, usecase.FeedBacklogConfig{MaxUnserved: 60, ExcludedIDsCap: 200}, logger)
	f.usecase = usecase.NewRefillFeed(f.units, manager, backlog, f.client, f.builder, usecase.RefillFeedConfig{
		LeaseWait:    100 * time.Millisecond,
		PollInterval: 5 * time.Millisecond,
		RetryAfter:   7 * time.Second,
	}, logger)
	return f
}

func (f *refillFixture) assertReleasedOnce(t *testing.T) {
	t.Helper()
	if len(f.lease.gotTokens) != 1 || f.lease.gotTokens[0] != f.lease.acquired[0].Token {
		t.Fatalf("released tokens = %v, want the acquired one once", f.lease.gotTokens)
	}
}

func assertDegraded(t *testing.T, err error) {
	t.Helper()
	var unavailable *usecase.FeedTemporarilyUnavailableError
	if !errors.As(err, &unavailable) || !errors.Is(err, usecase.ErrFeedTemporarilyUnavailable) {
		t.Fatalf("error = %v, want a FeedTemporarilyUnavailableError", err)
	}
	if unavailable.RetryAfter != 7*time.Second {
		t.Fatalf("RetryAfter = %s, want the configured 7s", unavailable.RetryAfter)
	}
}

func TestRefillFeedUsecase_Execute_StoresTheRecommendedPostsInOrderAndReleasesTheLease(t *testing.T) {
	f := newRefillFixture()

	status, err := f.usecase.Execute(context.Background(), f.input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if status != usecase.RefillFeedStatusUnitsAvailable {
		t.Fatalf("status = %s", status)
	}
	if len(f.builder.inputs) != 1 || !slices.Equal(f.builder.inputs[0].PostIDs, f.client.postIDs) || f.builder.inputs[0].UserID != f.input.UserID || f.builder.inputs[0].CorrelationID != "corr-1" {
		t.Fatalf("build inputs = %+v", f.builder.inputs)
	}
	f.assertReleasedOnce(t)
}

func TestRefillFeedUsecase_Execute_ExcludesTheUnservedAndRecentlyServedPosts(t *testing.T) {
	f := newRefillFixture()
	unserved, served := uuid.New(), uuid.New()
	f.backlog.unserved, f.backlog.served = []string{unserved.String()}, []string{served.String()}

	if _, err := f.usecase.Execute(context.Background(), f.input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	got := f.client.requests[0]
	if !slices.Equal(got.ExcludedPostIDs, []uuid.UUID{unserved, served}) || got.UserID != f.input.UserID || got.CorrelationID != "corr-1" {
		t.Fatalf("request = %+v", got)
	}
}

func TestRefillFeedUsecase_Execute_ReadsUnitsAFinishedBuildLeftWithoutCallingGRPC(t *testing.T) {
	f := newRefillFixture()
	f.units.found.Store(true)
	after := int64(41)
	f.input.AfterPosition, f.input.UnservedOnly = &after, true

	status, err := f.usecase.Execute(context.Background(), f.input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if status != usecase.RefillFeedStatusUnitsAvailable || len(f.client.requests) != 0 {
		t.Fatalf("status = %s, gRPC calls = %d, want units and no call", status, len(f.client.requests))
	}
	if f.units.gotAfter != &after || !f.units.gotUnread {
		t.Fatal("the check must read from where the claim reads")
	}
	f.assertReleasedOnce(t)
}

func TestRefillFeedUsecase_Execute_ReportsNoPostsAndReleasesTheLease(t *testing.T) {
	f := newRefillFixture()
	f.client.postIDs = nil

	status, err := f.usecase.Execute(context.Background(), f.input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if status != usecase.RefillFeedStatusNoPosts || len(f.builder.inputs) != 0 {
		t.Fatalf("status = %s, builds = %d", status, len(f.builder.inputs))
	}
	f.assertReleasedOnce(t)
}

func TestRefillFeedUsecase_Execute_ADegradedResponseReleasesTheLeaseAndLogsTheError(t *testing.T) {
	f := newRefillFixture()
	f.client.err = errors.New("UNAVAILABLE after 4 attempts")

	_, err := f.usecase.Execute(context.Background(), f.input)

	assertDegraded(t, err)
	f.assertReleasedOnce(t)
	for _, want := range []string{"level=ERROR", "feed degraded response", "correlation_id=corr-1", "userId=" + f.input.UserID.String()} {
		if !bytes.Contains(f.logs.Bytes(), []byte(want)) {
			t.Fatalf("log misses %q: %s", want, f.logs)
		}
	}
}

func TestRefillFeedUsecase_Execute_IsDegradedWhenNoRecommendedPostCouldBeStored(t *testing.T) {
	f := newRefillFixture()
	f.builder.output = usecase.BuildFeedUnitsOutput{Skipped: f.client.postIDs}

	_, err := f.usecase.Execute(context.Background(), f.input)

	assertDegraded(t, err)
	f.assertReleasedOnce(t)
}

func TestRefillFeedUsecase_Execute_ReturnsAStoreFailureAndReleasesTheLease(t *testing.T) {
	wantErr := errors.New("mongo failed")
	breaks := map[string]func(*refillFixture){
		"the stored feed check": func(f *refillFixture) { f.units.err = wantErr },
		"the excluded ids":      func(f *refillFixture) { f.backlog.err = wantErr },
		"storing the units":     func(f *refillFixture) { f.builder.err = wantErr },
	}
	for name, breakIt := range breaks {
		t.Run(name, func(t *testing.T) {
			f := newRefillFixture()
			breakIt(f)

			_, err := f.usecase.Execute(context.Background(), f.input)

			if !errors.Is(err, wantErr) || errors.Is(err, usecase.ErrFeedTemporarilyUnavailable) {
				t.Fatalf("error = %v, want the store failure", err)
			}
			f.assertReleasedOnce(t)
		})
	}
}

func TestRefillFeedUsecase_Execute_ACancelledRequestStillReleasesTheLeaseAndIsNotDegraded(t *testing.T) {
	f := newRefillFixture()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f.client.during = cancel
	f.client.err = errors.New("rpc error: code = Canceled")

	_, err := f.usecase.Execute(ctx, f.input)

	if !errors.Is(err, context.Canceled) || errors.Is(err, usecase.ErrFeedTemporarilyUnavailable) {
		t.Fatalf("error = %v, want the cancellation, not a degraded response", err)
	}
	f.assertReleasedOnce(t)
	if f.lease.releaseCtx[0] != nil || !f.lease.deadlines[0] {
		t.Fatalf("release context error = %v, bounded = %v, want a live and bounded context", f.lease.releaseCtx[0], f.lease.deadlines[0])
	}
}

func TestRefillFeedUsecase_Execute_ACallCancelledByTheServerIsDegraded(t *testing.T) {
	f := newRefillFixture()
	f.client.err = errors.New("rpc error: code = Canceled")

	_, err := f.usecase.Execute(context.Background(), f.input)

	assertDegraded(t, err)
}

func TestRefillFeedUsecase_Execute_PropagatesTheContextToTheClient(t *testing.T) {
	f := newRefillFixture()
	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")

	if _, err := f.usecase.Execute(ctx, f.input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if f.client.ctxs[0].Value(contextKey{}) != "propagated" {
		t.Fatal("the client did not receive the caller's context")
	}
}

func TestRefillFeedUsecase_Execute_AFailedReleaseDoesNotChangeTheResult(t *testing.T) {
	f := newRefillFixture()
	f.lease.releaseErr = errors.New("mongo failed")

	status, err := f.usecase.Execute(context.Background(), f.input)

	if err != nil || status != usecase.RefillFeedStatusUnitsAvailable {
		t.Fatalf("Execute() = %s, %v, want the units", status, err)
	}
}

func TestRefillFeedUsecase_Execute_AFailedAcquisitionCallsNothing(t *testing.T) {
	f := newRefillFixture()
	f.lease.acquireErr = errors.New("mongo failed")

	_, err := f.usecase.Execute(context.Background(), f.input)

	if !errors.Is(err, f.lease.acquireErr) || len(f.client.requests) != 0 || len(f.lease.gotTokens) != 0 {
		t.Fatalf("error = %v, calls = %d, releases = %d", err, len(f.client.requests), len(f.lease.gotTokens))
	}
}

func TestRefillFeedUsecase_Execute_RejectsAMissingUserID(t *testing.T) {
	f := newRefillFixture()

	if _, err := f.usecase.Execute(context.Background(), usecase.RefillFeedInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error")
	}
	if len(f.lease.acquired) != 0 {
		t.Fatal("the lease must not be taken")
	}
}

func TestRefillFeedUsecase_Execute_WithTheLeaseHeldServesUnitsThatAppearWithoutCallingGRPC(t *testing.T) {
	f := newRefillFixture()
	f.lease.acquireErr = usecase.ErrFeedLeaseDenied
	f.units.foundAt = 3

	status, err := f.usecase.Execute(context.Background(), f.input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if status != usecase.RefillFeedStatusUnitsAvailable {
		t.Fatalf("status = %s", status)
	}
	if len(f.client.requests) != 0 || len(f.builder.inputs) != 0 {
		t.Fatal("the holder is building this feed: no gRPC call and no build here")
	}
	if len(f.lease.gotTokens) != 0 {
		t.Fatal("a lease this call did not take must not be released")
	}
}

func TestRefillFeedUsecase_Execute_WithTheLeaseHeldIsDegradedWhenTheWaitEnds(t *testing.T) {
	f := newRefillFixture()
	f.lease.acquireErr = usecase.ErrFeedLeaseDenied

	started := time.Now()
	_, err := f.usecase.Execute(context.Background(), f.input)

	assertDegraded(t, err)
	if elapsed := time.Since(started); elapsed < 100*time.Millisecond || elapsed > refillTestTimeout {
		t.Fatalf("waited %s, want the configured 100ms", elapsed)
	}
	if len(f.client.requests) != 0 {
		t.Fatal("no gRPC call while the lease is held")
	}
	if f.units.reads.Load() < 2 {
		t.Fatalf("reads = %d, want it to poll", f.units.reads.Load())
	}
}

func TestRefillFeedUsecase_Execute_TheWaitHonoursACancelledRequest(t *testing.T) {
	f := newRefillFixture()
	f.lease.acquireErr = usecase.ErrFeedLeaseDenied
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := f.usecase.Execute(ctx, f.input)

	if !errors.Is(err, context.Canceled) || errors.Is(err, usecase.ErrFeedTemporarilyUnavailable) {
		t.Fatalf("error = %v, want the cancellation", err)
	}
}

func TestRefillFeedUsecase_Execute_TheWaitReturnsAReadFailure(t *testing.T) {
	f := newRefillFixture()
	f.lease.acquireErr = usecase.ErrFeedLeaseDenied
	f.units.err = errors.New("mongo failed")

	_, err := f.usecase.Execute(context.Background(), f.input)

	if !errors.Is(err, f.units.err) || errors.Is(err, usecase.ErrFeedTemporarilyUnavailable) {
		t.Fatalf("error = %v, want the read failure", err)
	}
}
