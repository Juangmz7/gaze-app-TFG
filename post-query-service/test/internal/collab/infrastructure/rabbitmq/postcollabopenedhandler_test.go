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

type fakeIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakeIdempotencyRepository() *fakeIdempotencyRepository {
	return &fakeIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakeIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type fakeUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.Input
	execErr error
}

func (f *fakeUsecase) Execute(ctx context.Context, input usecase.Input) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type ctxKey struct{}

func TestHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	uc := &fakeUsecase{}
	handler := rabbitmq.New(idempotency, uc, testLogger())

	event := validEvent()
	ctx := context.WithValue(context.Background(), ctxKey{}, "trace-value")

	if err := handler.Handle(ctx, newMessage(t, event)); err != nil {
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
	if uc.gotCtx.Value(ctxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the uc")
	}
	if idempotency.markedCtx.Value(ctxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validEvent()
	idempotency := newFakeIdempotencyRepository()
	idempotency.processed[event.ID] = true
	uc := &fakeUsecase{}
	handler := rabbitmq.New(idempotency, uc, testLogger())

	if err := handler.Handle(context.Background(), newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	uc := &fakeUsecase{}
	handler := rabbitmq.New(idempotency, uc, testLogger())

	event := validEvent()

	if err := handler.Handle(context.Background(), newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.CreatedAt.Unix() != event.CreatedAt.Unix() {
		t.Fatalf("uc Execute() CreatedAt = %v, want %v", uc.gotIn.CreatedAt, event.CreatedAt)
	}
}

func TestHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	uc := &fakeUsecase{}
	handler := rabbitmq.New(idempotency, uc, testLogger())

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

func TestHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	uc := &fakeUsecase{}
	handler := rabbitmq.New(idempotency, uc, testLogger())

	event := validEvent()
	event.CollabID = uuid.Nil

	err := handler.Handle(context.Background(), newMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing collab_id")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	uc := &fakeUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.New(idempotency, uc, testLogger())

	err := handler.Handle(context.Background(), newMessage(t, validEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validEvent() rabbitmq.Event {
	return rabbitmq.Event{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		CollabID:      uuid.New(),
		PostID:        uuid.New(),
		OwnerUserID:   uuid.New(),
		CreatedAt:     time.Now().UTC(),
	}
}

func newMessage(t *testing.T, event rabbitmq.Event) *message.Message {
	t.Helper()

	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return message.NewMessage(uuid.NewString(), payload)
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
