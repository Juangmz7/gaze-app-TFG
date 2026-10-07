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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakeCollabDeletedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakeCollabDeletedIdempotencyRepository() *fakeCollabDeletedIdempotencyRepository {
	return &fakeCollabDeletedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeCollabDeletedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeCollabDeletedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakeCollabDeletedUsecase struct {
	calls   int
	gotIn   usecase.DeleteCollabInput
	execErr error
}

func (f *fakeCollabDeletedUsecase) Execute(ctx context.Context, input usecase.DeleteCollabInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

func literalCollabDeletedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"collabId":      uuid.New().String(),
		"actionedBy":    uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
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

func TestCollabDeletedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakeCollabDeletedIdempotencyRepository()
	uc := &fakeCollabDeletedUsecase{}
	handler := rabbitmq.NewCollabDeletedHandler(idempotency, uc, testCollabDeletedLogger())

	collabID := uuid.New()
	payload := literalCollabDeletedEvent(t, map[string]any{"collabId": collabID.String()})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.CollabID != collabID {
		t.Fatalf("uc Execute() CollabID = %v, want %v", uc.gotIn.CollabID, collabID)
	}
}

func TestCollabDeletedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakeCollabDeletedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakeCollabDeletedUsecase{}
	handler := rabbitmq.NewCollabDeletedHandler(idempotency, uc, testCollabDeletedLogger())

	payload := literalCollabDeletedEvent(t, map[string]any{"id": eventID.String()})

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

func TestCollabDeletedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeCollabDeletedIdempotencyRepository()
	uc := &fakeCollabDeletedUsecase{}
	handler := rabbitmq.NewCollabDeletedHandler(idempotency, uc, testCollabDeletedLogger())

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

func TestCollabDeletedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeCollabDeletedIdempotencyRepository()
	uc := &fakeCollabDeletedUsecase{}
	handler := rabbitmq.NewCollabDeletedHandler(idempotency, uc, testCollabDeletedLogger())

	payload := literalCollabDeletedEvent(t, map[string]any{"actionedBy": uuid.Nil.String()})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing actionedBy")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestCollabDeletedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeCollabDeletedIdempotencyRepository()
	uc := &fakeCollabDeletedUsecase{execErr: errors.New("mongo delete failed")}
	handler := rabbitmq.NewCollabDeletedHandler(idempotency, uc, testCollabDeletedLogger())

	payload := literalCollabDeletedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testCollabDeletedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
