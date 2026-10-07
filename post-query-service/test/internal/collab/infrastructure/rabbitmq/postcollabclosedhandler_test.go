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

type fakePostCollabClosedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakePostCollabClosedIdempotencyRepository() *fakePostCollabClosedIdempotencyRepository {
	return &fakePostCollabClosedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostCollabClosedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostCollabClosedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakePostCollabClosedUsecase struct {
	calls   int
	gotIn   usecase.CloseCollabInput
	execErr error
}

func (f *fakePostCollabClosedUsecase) Execute(ctx context.Context, input usecase.CloseCollabInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalPostCollabClosedEvent builds the real Java-shaped camelCase JSON
// payload for CollabClosedEvent as a literal map, per the task's CRITICAL
// instruction: new handlers must be proven against real Java field names,
// not a round-tripped Go struct.
func literalPostCollabClosedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":              uuid.New().String(),
		"correlationId":   uuid.New().String(),
		"occurredAt":      time.Now().UTC().Format(time.RFC3339Nano),
		"collabId":        uuid.New().String(),
		"title":           "my collab",
		"createdBy":       uuid.New().String(),
		"closedBy":        uuid.New().String(),
		"collabStatus":    "CLOSED",
		"collabCreatedAt": time.Now().UTC().Format(time.RFC3339Nano),
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

func TestPostCollabClosedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakePostCollabClosedIdempotencyRepository()
	uc := &fakePostCollabClosedUsecase{}
	handler := rabbitmq.NewPostCollabClosedHandler(idempotency, uc, testPostCollabClosedLogger())

	collabID := uuid.New()
	createdBy := uuid.New()
	closedBy := uuid.New()
	payload := literalPostCollabClosedEvent(t, map[string]any{
		"collabId":     collabID.String(),
		"createdBy":    createdBy.String(),
		"closedBy":     closedBy.String(),
		"collabStatus": "CLOSED",
		"title":        "renamed collab",
	})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.CollabID != collabID {
		t.Fatalf("uc Execute() CollabID = %v, want %v", uc.gotIn.CollabID, collabID)
	}
	if uc.gotIn.CreatedBy != createdBy {
		t.Fatalf("uc Execute() CreatedBy = %v, want %v", uc.gotIn.CreatedBy, createdBy)
	}
	if uc.gotIn.ClosedBy != closedBy {
		t.Fatalf("uc Execute() ClosedBy = %v, want %v", uc.gotIn.ClosedBy, closedBy)
	}
	if uc.gotIn.Status != "CLOSED" {
		t.Fatalf("uc Execute() Status = %q, want %q", uc.gotIn.Status, "CLOSED")
	}
	if uc.gotIn.Title != "renamed collab" {
		t.Fatalf("uc Execute() Title = %q, want %q", uc.gotIn.Title, "renamed collab")
	}
}

func TestPostCollabClosedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakePostCollabClosedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakePostCollabClosedUsecase{}
	handler := rabbitmq.NewPostCollabClosedHandler(idempotency, uc, testPostCollabClosedLogger())

	payload := literalPostCollabClosedEvent(t, map[string]any{"id": eventID.String()})

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

func TestPostCollabClosedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostCollabClosedIdempotencyRepository()
	uc := &fakePostCollabClosedUsecase{}
	handler := rabbitmq.NewPostCollabClosedHandler(idempotency, uc, testPostCollabClosedLogger())

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

func TestPostCollabClosedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostCollabClosedIdempotencyRepository()
	uc := &fakePostCollabClosedUsecase{}
	handler := rabbitmq.NewPostCollabClosedHandler(idempotency, uc, testPostCollabClosedLogger())

	payload := literalPostCollabClosedEvent(t, map[string]any{"collabId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing collabId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostCollabClosedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostCollabClosedIdempotencyRepository()
	uc := &fakePostCollabClosedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostCollabClosedHandler(idempotency, uc, testPostCollabClosedLogger())

	payload := literalPostCollabClosedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testPostCollabClosedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
