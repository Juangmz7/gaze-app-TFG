package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakePrefetchWorld holds everything a prefetch touches. Its "transaction" discards
// what fn did when fn fails, and runs one lease at a time, like the unique index does.
type fakePrefetchWorld struct {
	mu sync.Mutex

	leaseHeld    bool
	due          map[uuid.UUID]bool
	prefetchedAt map[uuid.UUID]time.Time
	published    []usecase.FeedPrefetchRequest
	publishErr   error
	transactions int

	gotDeadline bool
	gotValue    any
}

func newFakePrefetchWorld() *fakePrefetchWorld {
	return &fakePrefetchWorld{due: map[uuid.UUID]bool{}, prefetchedAt: map[uuid.UUID]time.Time{}}
}

func (f *fakePrefetchWorld) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.transactions++
	_, f.gotDeadline = ctx.Deadline()
	f.gotValue = ctx.Value(contextKey{})

	lease, due, prefetchedAt, published := f.leaseHeld, cloneMap(f.due), cloneMap(f.prefetchedAt), len(f.published)
	err := fn(ctx)
	if err != nil {
		f.leaseHeld, f.due, f.prefetchedAt, f.published = lease, due, prefetchedAt, f.published[:published]
	}
	return err
}

func cloneMap[V any](source map[uuid.UUID]V) map[uuid.UUID]V {
	clone := make(map[uuid.UUID]V, len(source))
	for key, value := range source {
		clone[key] = value
	}
	return clone
}

func (f *fakePrefetchWorld) Acquire(_ context.Context, userID uuid.UUID) (usecase.FeedLease, error) {
	if f.leaseHeld {
		return usecase.FeedLease{}, usecase.ErrFeedLeaseDenied
	}
	f.leaseHeld = true
	return usecase.FeedLease{UserID: userID, Token: uuid.New()}, nil
}

func (f *fakePrefetchWorld) MarkPrefetched(_ context.Context, userID uuid.UUID, now time.Time) error {
	f.due[userID] = false
	f.prefetchedAt[userID] = now
	return nil
}

func (f *fakePrefetchWorld) ClearPrefetchDue(_ context.Context, userID uuid.UUID) error {
	f.due[userID] = false
	return nil
}

func (f *fakePrefetchWorld) PublishFeedPrefetch(_ context.Context, request usecase.FeedPrefetchRequest) error {
	if f.publishErr != nil {
		return f.publishErr
	}
	f.published = append(f.published, request)
	return nil
}

func newTestPrefetch(world *fakePrefetchWorld, repository *fakeBacklogRepository, maxUnserved int) *usecase.RequestFeedPrefetchUsecase {
	return newTestPrefetchWithLogger(world, repository, maxUnserved, discardLogger())
}

func newTestPrefetchWithLogger(world *fakePrefetchWorld, repository *fakeBacklogRepository, maxUnserved int, logger *slog.Logger) *usecase.RequestFeedPrefetchUsecase {
	backlog := usecase.NewFeedBacklog(repository, usecase.FeedBacklogConfig{MaxUnserved: maxUnserved, ExcludedIDsCap: 100}, logger)
	return usecase.NewRequestFeedPrefetch(world, backlog, world, world, world, time.Second, logger)
}

func TestRequestFeedPrefetchUsecase_Execute_PublishesTheRequestWithTheLeaseTokenAndExclusions(t *testing.T) {
	world := newFakePrefetchWorld()
	unserved, served := uuidStrings(2), uuidStrings(1)
	uc := newTestPrefetch(world, &fakeBacklogRepository{unserved: unserved, served: served}, 10)
	userID := uuid.New()
	world.due[userID] = true

	output, err := uc.Execute(context.Background(), usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if output.Status != usecase.RequestFeedPrefetchPublished || output.LeaseToken == uuid.Nil || output.CurrentFeedSize != 2 {
		t.Fatalf("output = %+v", output)
	}
	if len(world.published) != 1 {
		t.Fatalf("published = %+v, want one", world.published)
	}
	request := world.published[0]
	if request.CorrelationID != output.LeaseToken {
		t.Fatalf("correlationId = %v, want the lease token %v", request.CorrelationID, output.LeaseToken)
	}
	if request.UserID != userID || request.Reason != usecase.FeedPrefetchReasonDisconnect || request.CurrentFeedSize != 2 {
		t.Fatalf("request = %+v", request)
	}
	if len(request.ExcludedPostIDs) != 3 || request.ExcludedPostIDs[0].String() != unserved[0] || request.ExcludedPostIDs[2].String() != served[0] {
		t.Fatalf("excluded = %v, want the unserved and then the served", request.ExcludedPostIDs)
	}
	if world.due[userID] || world.prefetchedAt[userID].IsZero() {
		t.Fatalf("due = %v, prefetchedAt = %v; want the marker cleared", world.due[userID], world.prefetchedAt[userID])
	}
}

func TestRequestFeedPrefetchUsecase_Execute_SkipsAtTheMaximumAndClearsTheMarker(t *testing.T) {
	world := newFakePrefetchWorld()
	logger, logs := bufferLogger()
	uc := newTestPrefetchWithLogger(world, &fakeBacklogRepository{unserved: uuidStrings(3)}, 3, logger)
	userID := uuid.New()
	world.due[userID] = true

	output, err := uc.Execute(context.Background(), usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonStaleHeartbeat, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if output.Status != usecase.RequestFeedPrefetchSkipped {
		t.Fatalf("status = %s, want skipped", output.Status)
	}
	if len(world.published) != 0 || world.leaseHeld {
		t.Fatalf("published = %v, leaseHeld = %v; want neither", world.published, world.leaseHeld)
	}
	if world.due[userID] {
		t.Fatal("prefetch_due is still set, so every sweep would select the user again")
	}
	if !bytes.Contains(logs.Bytes(), []byte("feed prefetch skipped")) || !bytes.Contains(logs.Bytes(), []byte("reason=stale_heartbeat")) {
		t.Fatalf("logs = %s", logs)
	}
}

func TestRequestFeedPrefetchUsecase_Execute_AbortsCleanlyWhenTheLeaseIsHeld(t *testing.T) {
	world := newFakePrefetchWorld()
	world.leaseHeld = true
	logger, logs := bufferLogger()
	uc := newTestPrefetchWithLogger(world, &fakeBacklogRepository{}, 3, logger)
	userID := uuid.New()
	world.due[userID] = true

	output, err := uc.Execute(context.Background(), usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v, want a denied lease to be clean", err)
	}

	if output.Status != usecase.RequestFeedPrefetchDenied || len(world.published) != 0 {
		t.Fatalf("output = %+v, published = %v", output, world.published)
	}
	if !world.due[userID] {
		t.Fatal("prefetch_due was cleared by a trigger that built nothing")
	}
	if !bytes.Contains(logs.Bytes(), []byte("the lease is held")) || !bytes.Contains(logs.Bytes(), []byte("correlation_id=corr-1")) {
		t.Fatalf("logs = %s", logs)
	}
}

func TestRequestFeedPrefetchUsecase_Execute_AnOutboxFailureLeavesNoLeaseHeld(t *testing.T) {
	wantErr := errors.New("outbox insert failed")
	world := newFakePrefetchWorld()
	world.publishErr = wantErr
	uc := newTestPrefetch(world, &fakeBacklogRepository{}, 3)
	userID := uuid.New()
	world.due[userID] = true

	_, err := uc.Execute(context.Background(), usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect})

	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
	if world.leaseHeld || !world.due[userID] {
		t.Fatalf("leaseHeld = %v, due = %v; want the transaction rolled back", world.leaseHeld, world.due[userID])
	}
}

func TestRequestFeedPrefetchUsecase_Execute_RejectsAnInvalidInput(t *testing.T) {
	tests := []struct {
		name  string
		input usecase.RequestFeedPrefetchInput
	}{
		{"no user", usecase.RequestFeedPrefetchInput{Reason: usecase.FeedPrefetchReasonDisconnect}},
		{"unknown reason", usecase.RequestFeedPrefetchInput{UserID: uuid.New(), Reason: "because"}},
		{"synchronous reason", usecase.RequestFeedPrefetchInput{UserID: uuid.New(), Reason: "feed_empty"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			world := newFakePrefetchWorld()

			if _, err := newTestPrefetch(world, &fakeBacklogRepository{}, 3).Execute(context.Background(), tt.input); err == nil {
				t.Fatal("Execute() error = nil, want an error")
			}
			if world.transactions != 0 {
				t.Fatal("started a transaction for an invalid input")
			}
		})
	}
}

func TestRequestFeedPrefetchUsecase_Execute_PropagatesTheContextAndBoundsTheTransaction(t *testing.T) {
	world := newFakePrefetchWorld()
	repository := &fakeBacklogRepository{}
	uc := newTestPrefetch(world, repository, 3)
	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")

	if _, err := uc.Execute(ctx, usecase.RequestFeedPrefetchInput{UserID: uuid.New(), Reason: usecase.FeedPrefetchReasonDisconnect}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if world.gotValue != "propagated" || !world.gotDeadline {
		t.Fatalf("transaction context value = %v, deadline = %v", world.gotValue, world.gotDeadline)
	}
	for _, got := range repository.gotCtx {
		if got.Value(contextKey{}) != "propagated" {
			t.Fatal("the repository did not get the caller's context")
		}
	}
}

// One lease, however many triggers run: concurrent disconnect and sweep for one user
// publish exactly once. The same race on real MongoDB is in the integration tests.
func TestRequestFeedPrefetchUsecase_Execute_TriggersExactlyOnceForConcurrentDisconnectAndSweep(t *testing.T) {
	world := newFakePrefetchWorld()
	uc := newTestPrefetch(world, &fakeBacklogRepository{}, 3)
	userID := uuid.New()

	sweep := usecase.NewSweepStaleFeedSessions(fakeStaleSessions{{UserID: userID}}, uc, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute, BatchSize: 10}, discardLogger())
	disconnect := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{}, uc, time.Second, discardLogger())

	var wg sync.WaitGroup
	wg.Go(func() {
		if _, err := sweep.Execute(context.Background()); err != nil {
			t.Errorf("sweep error = %v", err)
		}
	})
	wg.Go(func() {
		if err := disconnect.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{UserID: userID}); err != nil {
			t.Errorf("disconnect error = %v", err)
		}
	})
	wg.Wait()

	if len(world.published) != 1 {
		t.Fatalf("published = %+v, want exactly one", world.published)
	}
}

func TestRequestFeedPrefetchUsecase_RequestUserRegisteredPrefetch_UsesTheSameFlowWithItsReason(t *testing.T) {
	world := newFakePrefetchWorld()
	uc := newTestPrefetch(world, &fakeBacklogRepository{}, 3)
	userID := uuid.New()

	if err := uc.RequestUserRegisteredPrefetch(context.Background(), userID, "corr-1"); err != nil {
		t.Fatalf("RequestUserRegisteredPrefetch() error = %v", err)
	}

	if len(world.published) != 1 || world.published[0].Reason != usecase.FeedPrefetchReasonUserRegistered || world.published[0].UserID != userID {
		t.Fatalf("published = %+v, want one with reason user_registered", world.published)
	}
}

func TestRequestFeedPrefetchUsecase_RequestUserRegisteredPrefetch_DoesNotFailOnADeniedLease(t *testing.T) {
	world := newFakePrefetchWorld()
	world.leaseHeld = true

	if err := newTestPrefetch(world, &fakeBacklogRepository{}, 3).RequestUserRegisteredPrefetch(context.Background(), uuid.New(), "corr-1"); err != nil {
		t.Fatalf("RequestUserRegisteredPrefetch() error = %v, want nil on a denied lease", err)
	}
}
