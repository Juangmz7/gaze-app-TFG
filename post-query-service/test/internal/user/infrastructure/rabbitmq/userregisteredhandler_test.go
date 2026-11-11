package rabbitmq_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/rabbitmq"
)

// literalUserRegisteredJSON is the exact JSON the Java publisher emits. It is deliberately
// a literal, never a marshalled handler struct: marshalling the handler's own
// struct is what hid the snake_case contract bug.
const literalUserRegisteredJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","userId":"44444444-4444-4444-8444-444444444444","username":"ana_rivera","email":"ana@example.test","bio":{"description":"Photographer","socialMedia":{"instagram":"ana.rivera"}}}`

type fakeUserRegisteredIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markCalls      int
	markErr        error
	markedID       uuid.UUID
	markedCorrelID uuid.UUID
	isProcessedCtx context.Context
	markedCtx      context.Context
}

func newFakeUserRegisteredIdempotencyRepository() *fakeUserRegisteredIdempotencyRepository {
	return &fakeUserRegisteredIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserRegisteredIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakeUserRegisteredIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.markedID = eventID
	f.markedCorrelID = correlationID
	return f.markErr
}

type fakeUserRegisteredUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RegisterUserInput
	execErr error
}

func (f *fakeUserRegisteredUsecase) Execute(ctx context.Context, input usecase.RegisterUserInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

func TestUserRegisteredHandler_Handle_DecodesTheLiteralJavaPayloadAndProjectsIt(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalUserRegisteredJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
	}

	if uc.calls != 1 {
		t.Fatalf("Execute() calls = %d, want 1", uc.calls)
	}
	got := uc.gotIn
	if got.UserID != uuid.MustParse("44444444-4444-4444-8444-444444444444") || got.Username != "ana_rivera" {
		t.Fatalf("Execute() UserID/Username = %v/%q", got.UserID, got.Username)
	}
	if got.Email != "ana@example.test" || got.BioDescription != "Photographer" || got.BioSocialMedia["instagram"] != "ana.rivera" {
		t.Fatalf("Execute() Email/Bio = %q/%q/%v", got.Email, got.BioDescription, got.BioSocialMedia)
	}
	if got.CorrelationID != "22222222-2222-4222-8222-222222222222" {
		t.Fatalf("Execute() CorrelationID = %q, want the event's, for the prefetch logs", got.CorrelationID)
	}
	wantCreatedAt := time.Date(2026, 3, 1, 10, 15, 30, 123456000, time.UTC)
	if !got.CreatedAt.Equal(wantCreatedAt) {
		t.Fatalf("Execute() CreatedAt = %v, want occurredAt %v because the event has no createdAt", got.CreatedAt, wantCreatedAt)
	}
	if idempotency.markedID != uuid.MustParse("11111111-1111-4111-8111-111111111111") || idempotency.markedCorrelID != uuid.MustParse("22222222-2222-4222-8222-222222222222") {
		t.Fatalf("MarkProcessed() id/correlationId = %v/%v", idempotency.markedID, idempotency.markedCorrelID)
	}
}

func TestUserRegisteredHandler_Handle_RejectsASnakeCasePayloadAsPermanent(t *testing.T) {
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(newFakeUserRegisteredIdempotencyRepository(), uc, testUserRegisteredLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","post_id":"33333333-3333-4333-8333-333333333333","user_id":"44444444-4444-4444-8444-444444444444","collab_id":"66666666-6666-4666-8666-666666666666","comment_id":"55555555-5555-4555-8555-555555555555","username":"ana_rivera","owner_user_id":"44444444-4444-4444-8444-444444444444","created_at":"2026-03-01T10:15:30Z"}`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error because the Java publishers emit camelCase", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0", uc.calls)
	}
}

func TestUserRegisteredHandler_Handle_PropagatesContextToTheUsecaseAndIdempotencyRepository(t *testing.T) {
	type contextKey struct{}
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())
	ctx := context.WithValue(context.Background(), contextKey{}, "trace-value")

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), []byte(literalUserRegisteredJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if uc.gotCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the usecase")
	}
	if idempotency.markedCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestUserRegisteredHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	idempotency.processed[uuid.MustParse("11111111-1111-4111-8111-111111111111")] = true
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalUserRegisteredJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestUserRegisteredHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(newFakeUserRegisteredIdempotencyRepository(), uc, testUserRegisteredLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`not json`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a malformed payload", uc.calls)
	}
}

func TestUserRegisteredHandler_Handle_ReturnsAPermanentErrorWhenARequiredFieldIsMissing(t *testing.T) {
	for _, field := range []string{"id", "correlationId", "occurredAt", "userId", "username"} {
		t.Run(field, func(t *testing.T) {
			uc := &fakeUserRegisteredUsecase{}
			handler := rabbitmq.NewUserRegisteredHandler(newFakeUserRegisteredIdempotencyRepository(), uc, testUserRegisteredLogger())

			payload := mutatedUserRegisteredPayload(t, func(fields map[string]any) { delete(fields, field) })

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

func TestUserRegisteredHandler_Handle_ReturnsATransientErrorAndDoesNotMarkWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalUserRegisteredJSON)))
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

// mutatedUserRegisteredPayload starts from the literal Java JSON and lets a test change
// individual fields.
func mutatedUserRegisteredPayload(t *testing.T, mutate func(fields map[string]any)) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal([]byte(literalUserRegisteredJSON), &fields); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	mutate(fields)

	payload, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return payload
}

func testUserRegisteredLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
