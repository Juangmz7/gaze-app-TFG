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

type fakePostCollabOpenedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakePostCollabOpenedIdempotencyRepository() *fakePostCollabOpenedIdempotencyRepository {
	return &fakePostCollabOpenedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostCollabOpenedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakePostCollabOpenedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type fakePostCollabOpenedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RecordCollabOpenedInput
	execErr error
}

func (f *fakePostCollabOpenedUsecase) Execute(ctx context.Context, input usecase.RecordCollabOpenedInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type postCollabOpenedCtxKey struct{}

func TestPostCollabOpenedHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	event := validPostCollabOpenedEvent()
	ctx := context.WithValue(context.Background(), postCollabOpenedCtxKey{}, "trace-value")

	if err := handler.Handle(ctx, newPostCollabOpenedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.CollabID != event.CollabID {
		t.Fatalf("uc Execute() CollabID = %v, want %v", uc.gotIn.CollabID, event.CollabID)
	}
	if uc.gotIn.PostID != event.PostID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, event.PostID)
	}
	if uc.gotIn.OwnerUserID != event.OwnerUserID {
		t.Fatalf("uc Execute() OwnerUserID = %v, want %v", uc.gotIn.OwnerUserID, event.OwnerUserID)
	}
	if !idempotency.processed[event.ID] {
		t.Fatal("idempotency record was not saved after a successful uc execution")
	}
	if uc.gotCtx.Value(postCollabOpenedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the uc")
	}
	if idempotency.markedCtx.Value(postCollabOpenedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestPostCollabOpenedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validPostCollabOpenedEvent()
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	idempotency.processed[event.ID] = true
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	if err := handler.Handle(context.Background(), newPostCollabOpenedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestPostCollabOpenedHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	event := validPostCollabOpenedEvent()

	if err := handler.Handle(context.Background(), newPostCollabOpenedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.CreatedAt.Unix() != event.CreatedAt.Unix() {
		t.Fatalf("uc Execute() CreatedAt = %v, want %v", uc.gotIn.CreatedAt, event.CreatedAt)
	}
}

func TestPostCollabOpenedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	msg := message.NewMessage("1", []byte(`not json`))

	err := handler.Handle(context.Background(), msg)
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

func TestPostCollabOpenedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	event := validPostCollabOpenedEvent()
	event.CollabID = uuid.Nil

	err := handler.Handle(context.Background(), newPostCollabOpenedMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing collab_id")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostCollabOpenedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	err := handler.Handle(context.Background(), newPostCollabOpenedMessage(t, validPostCollabOpenedEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validPostCollabOpenedEvent() rabbitmq.PostCollabOpenedEvent {
	return rabbitmq.PostCollabOpenedEvent{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		CollabID:      uuid.New(),
		PostID:        uuid.New(),
		OwnerUserID:   uuid.New(),
		CreatedAt:     time.Now().UTC(),
	}
}

func newPostCollabOpenedMessage(t *testing.T, event rabbitmq.PostCollabOpenedEvent) *message.Message {
	t.Helper()

	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return message.NewMessage(uuid.NewString(), payload)
}

func testPostCollabOpenedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
