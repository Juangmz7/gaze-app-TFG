package rabbitmq_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// literalPostLikeCreatedJSON is the exact JSON the Java publisher emits. It is deliberately
// a literal, never a marshalled handler struct: marshalling the handler's own
// struct is what hid the snake_case contract bug.
const literalPostLikeCreatedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","source":"HOME_FEED","feedPosition":3,"createdAt":"2026-03-01T10:15:00.5Z"}`

type fakePostLikeCreatedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markCalls      int
	markErr        error
	markedID       uuid.UUID
	markedCorrelID uuid.UUID
	isProcessedCtx context.Context
	markedCtx      context.Context
}

func newFakePostLikeCreatedIdempotencyRepository() *fakePostLikeCreatedIdempotencyRepository {
	return &fakePostLikeCreatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostLikeCreatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakePostLikeCreatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.markedID = eventID
	f.markedCorrelID = correlationID
	return f.markErr
}

type fakePostLikeCreatedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RecordLikeInput
	execErr error
}

func (f *fakePostLikeCreatedUsecase) Execute(ctx context.Context, input usecase.RecordLikeInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type postLikeCreatedTransactionKey struct{}

// fakePostLikeCreatedTransactor marks the context it hands to fn, the way a real transaction
// carries its session, so tests can prove which calls ran inside it.
type fakePostLikeCreatedTransactor struct{}

func (fakePostLikeCreatedTransactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(context.WithValue(ctx, postLikeCreatedTransactionKey{}, true))
}

func TestPostLikeCreatedHandler_Handle_DecodesTheLiteralJavaPayloadAndProjectsIt(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, uc, testPostLikeCreatedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
	}

	if uc.calls != 1 {
		t.Fatalf("Execute() calls = %d, want 1", uc.calls)
	}
	got := uc.gotIn
	if got.PostID != uuid.MustParse("33333333-3333-4333-8333-333333333333") || got.UserID != uuid.MustParse("44444444-4444-4444-8444-444444444444") {
		t.Fatalf("Execute() PostID/UserID = %v/%v", got.PostID, got.UserID)
	}
	if got.CreatedAt.IsZero() {
		t.Fatal("Execute() CreatedAt is zero, want the event's createdAt")
	}
	if idempotency.markedID != uuid.MustParse("11111111-1111-4111-8111-111111111111") || idempotency.markedCorrelID != uuid.MustParse("22222222-2222-4222-8222-222222222222") {
		t.Fatalf("MarkProcessed() id/correlationId = %v/%v", idempotency.markedID, idempotency.markedCorrelID)
	}
}

func TestPostLikeCreatedHandler_Handle_RejectsASnakeCasePayloadAsPermanent(t *testing.T) {
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, newFakePostLikeCreatedIdempotencyRepository(), uc, testPostLikeCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","post_id":"33333333-3333-4333-8333-333333333333","user_id":"44444444-4444-4444-8444-444444444444","collab_id":"66666666-6666-4666-8666-666666666666","comment_id":"55555555-5555-4555-8555-555555555555","username":"ana_rivera","owner_user_id":"44444444-4444-4444-8444-444444444444","created_at":"2026-03-01T10:15:30Z"}`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error because the Java publishers emit camelCase", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0", uc.calls)
	}
}

func TestPostLikeCreatedHandler_Handle_PropagatesContextToTheUsecaseAndIdempotencyRepository(t *testing.T) {
	type contextKey struct{}
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, uc, testPostLikeCreatedLogger())
	ctx := context.WithValue(context.Background(), contextKey{}, "trace-value")

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if uc.gotCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the usecase")
	}
	if idempotency.markedCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestPostLikeCreatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	idempotency.processed[uuid.MustParse("11111111-1111-4111-8111-111111111111")] = true
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, uc, testPostLikeCreatedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, newFakePostLikeCreatedIdempotencyRepository(), uc, testPostLikeCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`not json`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a malformed payload", uc.calls)
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsAPermanentErrorWhenARequiredFieldIsMissing(t *testing.T) {
	for _, field := range []string{"id", "correlationId", "occurredAt", "postId", "userId"} {
		t.Run(field, func(t *testing.T) {
			uc := &fakePostLikeCreatedUsecase{}
			handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, newFakePostLikeCreatedIdempotencyRepository(), uc, testPostLikeCreatedLogger())

			payload := mutatedPostLikeCreatedPayload(t, func(fields map[string]any) { delete(fields, field) })

			err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error without %s", err, field)
			}
			if uc.calls != 0 {
				t.Fatalf("Execute() calls = %d, want 0", uc.calls)
			}
		})
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsATransientErrorAndDoesNotMarkWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, uc, testPostLikeCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON)))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the usecase fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 when the usecase failed", idempotency.markCalls)
	}
}

func TestPostLikeCreatedHandler_Handle_RunsTheIdempotencyCheckProjectionAndMarkInOneTransaction(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, uc, testPostLikeCreatedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if idempotency.isProcessedCtx.Value(postLikeCreatedTransactionKey{}) != true {
		t.Fatal("IsProcessed() ran outside the transaction")
	}
	if uc.gotCtx.Value(postLikeCreatedTransactionKey{}) != true {
		t.Fatal("Execute() ran outside the transaction")
	}
	if idempotency.markedCtx.Value(postLikeCreatedTransactionKey{}) != true {
		t.Fatal("MarkProcessed() ran outside the transaction")
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsATransientErrorWhenMarkProcessedFails(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	idempotency.markErr = errors.New("mongo write failed")
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, &fakePostLikeCreatedUsecase{}, testPostLikeCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON)))
	if err == nil || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a transient error so the transaction aborts and retries apply", err)
	}
}

// mutatedPostLikeCreatedPayload starts from the literal Java JSON and lets a test change
// individual fields.
func mutatedPostLikeCreatedPayload(t *testing.T, mutate func(fields map[string]any)) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal([]byte(literalPostLikeCreatedJSON), &fields); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	mutate(fields)

	payload, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return payload
}

func testPostLikeCreatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
