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

type fakePostDeletedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakePostDeletedIdempotencyRepository() *fakePostDeletedIdempotencyRepository {
	return &fakePostDeletedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostDeletedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostDeletedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakePostDeletedUsecase struct {
	calls   int
	gotIn   usecase.DeletePostInput
	execErr error
}

func (f *fakePostDeletedUsecase) Execute(ctx context.Context, input usecase.DeletePostInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalPostDeletedEvent builds the real Java-shaped camelCase JSON
// payload for PostDeletedEvent as a literal map.
func literalPostDeletedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"postId":        uuid.New().String(),
		"userId":        uuid.New().String(),
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

func TestPostDeletedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakePostDeletedIdempotencyRepository()
	uc := &fakePostDeletedUsecase{}
	handler := rabbitmq.NewPostDeletedHandler(idempotency, uc, testPostDeletedLogger())

	postID := uuid.New()
	payload := literalPostDeletedEvent(t, map[string]any{"postId": postID.String()})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.PostID != postID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, postID)
	}
}

func TestPostDeletedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakePostDeletedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakePostDeletedUsecase{}
	handler := rabbitmq.NewPostDeletedHandler(idempotency, uc, testPostDeletedLogger())

	payload := literalPostDeletedEvent(t, map[string]any{"id": eventID.String()})

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

func TestPostDeletedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostDeletedIdempotencyRepository()
	uc := &fakePostDeletedUsecase{}
	handler := rabbitmq.NewPostDeletedHandler(idempotency, uc, testPostDeletedLogger())

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

func TestPostDeletedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostDeletedIdempotencyRepository()
	uc := &fakePostDeletedUsecase{}
	handler := rabbitmq.NewPostDeletedHandler(idempotency, uc, testPostDeletedLogger())

	payload := literalPostDeletedEvent(t, map[string]any{"userId": uuid.Nil.String()})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing userId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostDeletedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostDeletedIdempotencyRepository()
	uc := &fakePostDeletedUsecase{execErr: errors.New("mongo delete failed")}
	handler := rabbitmq.NewPostDeletedHandler(idempotency, uc, testPostDeletedLogger())

	payload := literalPostDeletedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testPostDeletedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
