package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

type fakeFeedLeaseRepository struct {
	acquired   []usecase.FeedLease
	acquireNow []time.Time
	acquireErr error
	released   bool
	releaseErr error
	releaseCtx []error
	deadlines  []bool
	gotTokens  []uuid.UUID
}

func (f *fakeFeedLeaseRepository) AcquireFeedLease(_ context.Context, lease usecase.FeedLease, now time.Time) error {
	f.acquired = append(f.acquired, lease)
	f.acquireNow = append(f.acquireNow, now)
	return f.acquireErr
}

func (f *fakeFeedLeaseRepository) ReleaseFeedLease(ctx context.Context, _, token uuid.UUID) (bool, error) {
	_, hasDeadline := ctx.Deadline()
	f.releaseCtx = append(f.releaseCtx, ctx.Err())
	f.deadlines = append(f.deadlines, hasDeadline)
	f.gotTokens = append(f.gotTokens, token)
	return f.released, f.releaseErr
}

var testLeaseConfig = usecase.FeedLeaseConfig{Owner: "replica-1", TTL: time.Minute, RequestTTL: 30 * time.Second, ReleaseTimeout: time.Second}

func TestFeedLeaseManager_Acquire_TakesTheLeaseWithANewTokenAndTheConfiguredExpiry(t *testing.T) {
	repository := &fakeFeedLeaseRepository{}
	manager := usecase.NewFeedLeaseManager(repository, testLeaseConfig, discardLogger())
	userID := uuid.New()

	first, err := manager.Acquire(context.Background(), userID)
	if err != nil {
		t.Fatalf("Acquire() error = %v", err)
	}
	second, err := manager.Acquire(context.Background(), userID)
	if err != nil {
		t.Fatalf("Acquire() error = %v", err)
	}

	if first.UserID != userID || first.Owner != "replica-1" || first.Token == uuid.Nil {
		t.Fatalf("lease = %+v", first)
	}
	if first.Token == second.Token {
		t.Fatal("two acquisitions share a token: a late response could release the newer lease")
	}
	if got := first.ExpiresAt.Sub(repository.acquireNow[0]); got != time.Minute {
		t.Fatalf("lease expires %s after now, want the TTL", got)
	}
}

// A synchronous refill only has to outlive its call: a holder that dies mid-call must
// not block the user's empty feed for the asynchronous TTL.
func TestFeedLeaseManager_AcquireForRequest_ExpiresAfterTheRequestTTL(t *testing.T) {
	repository := &fakeFeedLeaseRepository{}
	manager := usecase.NewFeedLeaseManager(repository, testLeaseConfig, discardLogger())

	lease, acquired, err := manager.AcquireForRequest(context.Background(), uuid.New(), "corr-1")
	if err != nil || !acquired {
		t.Fatalf("AcquireForRequest() = %v, %v; want acquired", acquired, err)
	}
	if got := lease.ExpiresAt.Sub(repository.acquireNow[0]); got != testLeaseConfig.RequestTTL {
		t.Fatalf("lease lasts %s, want the request TTL %s", got, testLeaseConfig.RequestTTL)
	}
}

func TestFeedLeaseManager_Acquire_RejectsAMissingUserID(t *testing.T) {
	repository := &fakeFeedLeaseRepository{}
	manager := usecase.NewFeedLeaseManager(repository, testLeaseConfig, discardLogger())

	if _, err := manager.Acquire(context.Background(), uuid.Nil); err == nil {
		t.Fatal("Acquire() error = nil, want an error")
	}
	if len(repository.acquired) != 0 {
		t.Fatal("reached the repository without a user")
	}
}

func TestFeedLeaseManager_AcquireForRequest_ReportsADeniedLeaseWithoutAnError(t *testing.T) {
	logger, logs := bufferLogger()
	manager := usecase.NewFeedLeaseManager(&fakeFeedLeaseRepository{acquireErr: usecase.ErrFeedLeaseDenied}, testLeaseConfig, logger)
	userID := uuid.New()

	_, acquired, err := manager.AcquireForRequest(context.Background(), userID, "corr-1")

	if err != nil || acquired {
		t.Fatalf("AcquireForRequest() = %v, %v; want denied without an error", acquired, err)
	}
	if !bytes.Contains(logs.Bytes(), []byte("feed lease denied")) || !bytes.Contains(logs.Bytes(), []byte("correlation_id=corr-1")) || !bytes.Contains(logs.Bytes(), []byte("userId="+userID.String())) {
		t.Fatalf("logs = %s", logs)
	}
}

func TestFeedLeaseManager_AcquireForRequest_ReturnsARealFailure(t *testing.T) {
	wantErr := errors.New("mongo failed")
	manager := usecase.NewFeedLeaseManager(&fakeFeedLeaseRepository{acquireErr: wantErr}, testLeaseConfig, discardLogger())

	_, acquired, err := manager.AcquireForRequest(context.Background(), uuid.New(), "corr-1")

	if !errors.Is(err, wantErr) || acquired {
		t.Fatalf("AcquireForRequest() = %v, %v; want the failure", acquired, err)
	}
}

func TestFeedLeaseManager_AcquireForRequest_ReturnsTheLeaseToRelease(t *testing.T) {
	repository := &fakeFeedLeaseRepository{released: true}
	manager := usecase.NewFeedLeaseManager(repository, testLeaseConfig, discardLogger())
	userID := uuid.New()

	lease, acquired, err := manager.AcquireForRequest(context.Background(), userID, "corr-1")
	if err != nil || !acquired {
		t.Fatalf("AcquireForRequest() = %v, %v; want acquired", acquired, err)
	}

	released, err := manager.Release(context.Background(), userID, lease.Token, "corr-1")
	if err != nil || !released {
		t.Fatalf("Release() = %v, %v; want released", released, err)
	}
	if repository.gotTokens[0] != lease.Token {
		t.Fatalf("released token %v, want %v", repository.gotTokens[0], lease.Token)
	}
}

func TestFeedLeaseManager_Release_WorksWithACancelledContextAndABoundedOne(t *testing.T) {
	repository := &fakeFeedLeaseRepository{released: true}
	manager := usecase.NewFeedLeaseManager(repository, testLeaseConfig, discardLogger())

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	released, err := manager.Release(ctx, uuid.New(), uuid.New(), "corr-1")

	if err != nil || !released {
		t.Fatalf("Release() = %v, %v; want released", released, err)
	}
	if repository.releaseCtx[0] != nil {
		t.Fatalf("repository context error = %v, want a live context", repository.releaseCtx[0])
	}
	if !repository.deadlines[0] {
		t.Fatal("repository context has no deadline, want the release timeout")
	}
}

func TestFeedLeaseManager_Release_ReportsATokenThatNoLongerHoldsTheLease(t *testing.T) {
	manager := usecase.NewFeedLeaseManager(&fakeFeedLeaseRepository{released: false}, testLeaseConfig, discardLogger())

	released, err := manager.Release(context.Background(), uuid.New(), uuid.New(), "corr-1")

	if err != nil || released {
		t.Fatalf("Release() = %v, %v; want not released and no error", released, err)
	}
}

func TestFeedLeaseManager_Release_ReturnsAndLogsAFailure(t *testing.T) {
	wantErr := errors.New("mongo failed")
	logger, logs := bufferLogger()
	manager := usecase.NewFeedLeaseManager(&fakeFeedLeaseRepository{releaseErr: wantErr}, testLeaseConfig, logger)

	if _, err := manager.Release(context.Background(), uuid.New(), uuid.New(), "corr-1"); !errors.Is(err, wantErr) {
		t.Fatalf("Release() error = %v, want it to wrap %v", err, wantErr)
	}
	if !bytes.Contains(logs.Bytes(), []byte("level=ERROR")) {
		t.Fatalf("logs = %s, want an error line", logs)
	}
}
