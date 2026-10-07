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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakeUserBlockCreatedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakeUserBlockCreatedIdempotencyRepository() *fakeUserBlockCreatedIdempotencyRepository {
	return &fakeUserBlockCreatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserBlockCreatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeUserBlockCreatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakeUserBlockCreatedUsecase struct {
	calls   int
	gotIn   usecase.CreateBlockInput
	execErr error
}

func (f *fakeUserBlockCreatedUsecase) Execute(ctx context.Context, input usecase.CreateBlockInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalUserBlockCreatedEvent builds the real Java-shaped camelCase JSON
// payload for UserBlockedEvent as a literal map, per the task's CRITICAL
// instruction: new handlers must be proven against real Java field names,
// not a round-tripped Go struct.
func literalUserBlockCreatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
		"blockerUserId": uuid.New().String(),
		"blockedUserId": uuid.New().String(),
	}
	for k, v := range overrides {
		payload[k] = v
	}

	body, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return body
}

func TestUserBlockCreatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakeUserBlockCreatedIdempotencyRepository()
	uc := &fakeUserBlockCreatedUsecase{}
	handler := rabbitmq.NewUserBlockCreatedHandler(idempotency, uc, testUserBlockCreatedLogger())

	blockerID := uuid.New()
	blockedID := uuid.New()
	payload := literalUserBlockCreatedEvent(t, map[string]any{
		"blockerUserId": blockerID.String(),
		"blockedUserId": blockedID.String(),
	})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.BlockerUserID != blockerID {
		t.Fatalf("uc Execute() BlockerUserID = %v, want %v", uc.gotIn.BlockerUserID, blockerID)
	}
	if uc.gotIn.BlockedUserID != blockedID {
		t.Fatalf("uc Execute() BlockedUserID = %v, want %v", uc.gotIn.BlockedUserID, blockedID)
	}
}

func TestUserBlockCreatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakeUserBlockCreatedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakeUserBlockCreatedUsecase{}
	handler := rabbitmq.NewUserBlockCreatedHandler(idempotency, uc, testUserBlockCreatedLogger())

	payload := literalUserBlockCreatedEvent(t, map[string]any{"id": eventID.String()})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}
	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestUserBlockCreatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserBlockCreatedIdempotencyRepository()
	uc := &fakeUserBlockCreatedUsecase{}
	handler := rabbitmq.NewUserBlockCreatedHandler(idempotency, uc, testUserBlockCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage("1", []byte(`not json`)))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a malformed payload")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error so it skips retries")
	}
	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a malformed payload", uc.calls)
	}
}

func TestUserBlockCreatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserBlockCreatedIdempotencyRepository()
	uc := &fakeUserBlockCreatedUsecase{}
	handler := rabbitmq.NewUserBlockCreatedHandler(idempotency, uc, testUserBlockCreatedLogger())

	payload := literalUserBlockCreatedEvent(t, map[string]any{"blockedUserId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing blockedUserId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserBlockCreatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserBlockCreatedIdempotencyRepository()
	uc := &fakeUserBlockCreatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserBlockCreatedHandler(idempotency, uc, testUserBlockCreatedLogger())

	payload := literalUserBlockCreatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testUserBlockCreatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
