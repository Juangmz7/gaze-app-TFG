package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func bufferLogger() (*slog.Logger, *bytes.Buffer) {
	buffer := &bytes.Buffer{}
	return slog.New(slog.NewTextHandler(buffer, &slog.HandlerOptions{Level: slog.LevelDebug})), buffer
}

type contextKey struct{}

type fakeSessionRepository struct {
	gotCtx    context.Context
	gotUserID uuid.UUID
	gotNow    time.Time
	gotCtxErr error
	calls     int
	err       error
}

func (f *fakeSessionRepository) record(ctx context.Context, userID uuid.UUID, now time.Time) error {
	f.calls++
	f.gotCtx = ctx
	f.gotCtxErr = ctx.Err()
	f.gotUserID = userID
	f.gotNow = now
	return f.err
}

func (f *fakeSessionRepository) RecordHeartbeat(ctx context.Context, userID uuid.UUID, now time.Time) error {
	return f.record(ctx, userID, now)
}

func (f *fakeSessionRepository) RecordDisconnect(ctx context.Context, userID uuid.UUID, now time.Time) error {
	return f.record(ctx, userID, now)
}

func TestRegisterHeartbeatUsecase_Execute_RecordsTheHeartbeatAtNowInUTC(t *testing.T) {
	repository := &fakeSessionRepository{}
	logger, logs := bufferLogger()
	uc := usecase.NewRegisterHeartbeat(repository, logger)

	userID := uuid.New()
	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")
	before := time.Now().UTC()

	if err := uc.Execute(ctx, usecase.RegisterHeartbeatInput{UserID: userID, CorrelationID: "corr-1"}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if repository.calls != 1 || repository.gotUserID != userID {
		t.Fatalf("RecordHeartbeat() calls = %d, userID = %v, want 1 call for %v", repository.calls, repository.gotUserID, userID)
	}
	if repository.gotNow.Before(before) || repository.gotNow.Location() != time.UTC {
		t.Fatalf("RecordHeartbeat() now = %v, want a UTC time not before %v", repository.gotNow, before)
	}
	if repository.gotCtx.Value(contextKey{}) != "propagated" {
		t.Fatal("RecordHeartbeat() did not receive the caller's context")
	}
	if !bytes.Contains(logs.Bytes(), []byte("correlation_id=corr-1")) || !bytes.Contains(logs.Bytes(), []byte("userId="+userID.String())) {
		t.Fatalf("log does not carry correlation_id and userId: %s", logs)
	}
}

func TestRegisterHeartbeatUsecase_Execute_RejectsAMissingUserID(t *testing.T) {
	repository := &fakeSessionRepository{}
	uc := usecase.NewRegisterHeartbeat(repository, discardLogger())

	if err := uc.Execute(context.Background(), usecase.RegisterHeartbeatInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error")
	}
	if repository.calls != 0 {
		t.Fatalf("RecordHeartbeat() calls = %d, want 0", repository.calls)
	}
}

func TestRegisterHeartbeatUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo failed")
	uc := usecase.NewRegisterHeartbeat(&fakeSessionRepository{err: wantErr}, discardLogger())

	err := uc.Execute(context.Background(), usecase.RegisterHeartbeatInput{UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_WritesEvenWithACancelledRequestContext(t *testing.T) {
	repository := &fakeSessionRepository{}
	uc := usecase.NewRegisterUserDisconnected(repository, &fakeDisconnectPrefetch{}, 3*time.Second, discardLogger())

	userID := uuid.New()
	ctx, cancel := context.WithCancel(context.WithValue(context.Background(), contextKey{}, "propagated"))
	cancel()

	if err := uc.Execute(ctx, usecase.RegisterUserDisconnectedInput{UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if repository.calls != 1 || repository.gotUserID != userID {
		t.Fatalf("RecordDisconnect() calls = %d, userID = %v, want 1 call for %v", repository.calls, repository.gotUserID, userID)
	}
	if repository.gotCtxErr != nil {
		t.Fatalf("RecordDisconnect() context error = %v, want a live context", repository.gotCtxErr)
	}
	deadline, hasDeadline := repository.gotCtx.Deadline()
	if !hasDeadline || time.Until(deadline) > 3*time.Second {
		t.Fatalf("RecordDisconnect() deadline = %v (set: %v), want the configured timeout", deadline, hasDeadline)
	}
	if repository.gotCtx.Value(contextKey{}) != "propagated" {
		t.Fatal("RecordDisconnect() lost the caller's context values")
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_RejectsAMissingUserID(t *testing.T) {
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{}, &fakeDisconnectPrefetch{}, time.Second, discardLogger())

	if err := uc.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error")
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo failed")
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{err: wantErr}, &fakeDisconnectPrefetch{}, time.Second, discardLogger())

	err := uc.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

type fakeDisconnectPrefetch struct {
	inputs  []usecase.RequestFeedPrefetchInput
	ctxErrs []error
	err     error
}

func (f *fakeDisconnectPrefetch) Execute(ctx context.Context, input usecase.RequestFeedPrefetchInput) (usecase.RequestFeedPrefetchOutput, error) {
	f.inputs = append(f.inputs, input)
	f.ctxErrs = append(f.ctxErrs, ctx.Err())
	return usecase.RequestFeedPrefetchOutput{Status: usecase.RequestFeedPrefetchPublished}, f.err
}

func TestRegisterUserDisconnectedUsecase_Execute_RequestsAPrefetchWithReasonDisconnect(t *testing.T) {
	prefetch := &fakeDisconnectPrefetch{}
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{}, prefetch, time.Second, discardLogger())
	userID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{UserID: userID, CorrelationID: "corr-1"}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	want := usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonDisconnect, CorrelationID: "corr-1"}
	if len(prefetch.inputs) != 1 || prefetch.inputs[0] != want {
		t.Fatalf("prefetch inputs = %+v, want [%+v]", prefetch.inputs, want)
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_LogsAFailedPrefetchInsteadOfReturningIt(t *testing.T) {
	prefetch := &fakeDisconnectPrefetch{err: errors.New("mongo failed")}
	logger, logs := bufferLogger()
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{}, prefetch, time.Second, logger)

	if err := uc.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{UserID: uuid.New(), CorrelationID: "corr-1"}); err != nil {
		t.Fatalf("Execute() error = %v, want the failure logged, not returned", err)
	}

	if !bytes.Contains(logs.Bytes(), []byte("level=ERROR")) || !bytes.Contains(logs.Bytes(), []byte("correlation_id=corr-1")) {
		t.Fatalf("logs = %s, want an error line with the correlation id", logs)
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_RequestsThePrefetchEvenWithACancelledRequestContext(t *testing.T) {
	prefetch := &fakeDisconnectPrefetch{}
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{}, prefetch, time.Second, discardLogger())

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := uc.Execute(ctx, usecase.RegisterUserDisconnectedInput{UserID: uuid.New()}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(prefetch.ctxErrs) != 1 || prefetch.ctxErrs[0] != nil {
		t.Fatalf("prefetch context errors = %v, want one live context", prefetch.ctxErrs)
	}
}

func TestRegisterUserDisconnectedUsecase_Execute_RequestsNoPrefetchWhenTheWriteFails(t *testing.T) {
	prefetch := &fakeDisconnectPrefetch{}
	uc := usecase.NewRegisterUserDisconnected(&fakeSessionRepository{err: errors.New("mongo failed")}, prefetch, time.Second, discardLogger())

	if err := uc.Execute(context.Background(), usecase.RegisterUserDisconnectedInput{UserID: uuid.New()}); err == nil {
		t.Fatal("Execute() error = nil, want the write failure")
	}
	if len(prefetch.inputs) != 0 {
		t.Fatalf("prefetch inputs = %+v, want none", prefetch.inputs)
	}
}
