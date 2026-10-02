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

type fakeIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	isProcessedErr error
	markProcessErr error
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakeIdempotencyRepository() *fakeIdempotencyRepository {
	return &fakeIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	if f.isProcessedErr != nil {
		return false, f.isProcessedErr
	}
	return f.processed[eventID], nil
}

func (f *fakeIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	if f.markProcessErr != nil {
		return f.markProcessErr
	}
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
	if uc.gotIn.PostID != event.PostID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, event.PostID)
	}
	if uc.gotIn.UserID != event.UserID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, event.UserID)
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
	event.Description = "a distributed systems post"
	event.PostTags = []string{"go", "rabbitmq"}

	if err := handler.Handle(context.Background(), newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.Description != event.Description {
		t.Fatalf("uc Execute() Description = %q, want %q", uc.gotIn.Description, event.Description)
	}
	if len(uc.gotIn.Tags) != 2 || uc.gotIn.Tags[0] != "go" || uc.gotIn.Tags[1] != "rabbitmq" {
		t.Fatalf("uc Execute() Tags = %v, want [go rabbitmq]", uc.gotIn.Tags)
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
	event.PostID = uuid.Nil

	err := handler.Handle(context.Background(), newMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing post_id")
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
		PostID:        uuid.New(),
		UserID:        uuid.New(),
		PostType:      "TEXT",
		CreatedAt:     time.Now().UTC(),
		UpdatedAt:     time.Now().UTC(),
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
