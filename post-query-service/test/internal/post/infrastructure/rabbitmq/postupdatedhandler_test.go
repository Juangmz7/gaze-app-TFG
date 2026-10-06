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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakePostUpdatedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakePostUpdatedIdempotencyRepository() *fakePostUpdatedIdempotencyRepository {
	return &fakePostUpdatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostUpdatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostUpdatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakePostUpdatedUsecase struct {
	calls   int
	gotIn   usecase.UpdatePostInput
	execErr error
}

func (f *fakePostUpdatedUsecase) Execute(ctx context.Context, input usecase.UpdatePostInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalPostUpdatedEvent builds the real Java-shaped camelCase JSON
// payload for PostUpdatedEvent as a literal map, per the task's requirement
// that new handlers be proven against real Java field names, not a
// round-tripped Go struct.
func literalPostUpdatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
		"postId":        uuid.New().String(),
		"userId":        uuid.New().String(),
		"collabId":      uuid.New().String(),
		"postType":      "TEXT",
		"description":   "updated description",
		"postTags":      []string{"go", "rabbitmq"},
		"createdAt":     time.Now().UTC().Format(time.RFC3339Nano),
		"updatedAt":     time.Now().UTC().Format(time.RFC3339Nano),
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

func TestPostUpdatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	postID := uuid.New()
	payload := literalPostUpdatedEvent(t, map[string]any{"postId": postID.String(), "description": "new description"})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.PostID != postID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, postID)
	}
	if uc.gotIn.Description != "new description" {
		t.Fatalf("uc Execute() Description = %q, want %q", uc.gotIn.Description, "new description")
	}
}

func TestPostUpdatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakePostUpdatedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, map[string]any{"id": eventID.String()})

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

func TestPostUpdatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

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

func TestPostUpdatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, map[string]any{"postId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing postId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostUpdatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testPostUpdatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
