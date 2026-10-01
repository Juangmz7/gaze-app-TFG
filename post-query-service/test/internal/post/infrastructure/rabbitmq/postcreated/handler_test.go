package postcreated_test

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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase/createpost"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/rabbitmq/postcreated"
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
	gotIn   createpost.Input
	execErr error
}

func (f *fakeUsecase) Execute(ctx context.Context, input createpost.Input) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type ctxKey struct{}

func TestHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	usecase := &fakeUsecase{}
	handler := postcreated.New(idempotency, usecase, testLogger())

	event := validEvent()
	ctx := context.WithValue(context.Background(), ctxKey{}, "trace-value")

	if err := handler.Handle(ctx, newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if usecase.calls != 1 {
		t.Fatalf("usecase Execute() calls = %d, want 1", usecase.calls)
	}
	if usecase.gotIn.PostID != event.PostID {
		t.Fatalf("usecase Execute() PostID = %v, want %v", usecase.gotIn.PostID, event.PostID)
	}
	if usecase.gotIn.UserID != event.UserID {
		t.Fatalf("usecase Execute() UserID = %v, want %v", usecase.gotIn.UserID, event.UserID)
	}
	if !idempotency.processed[event.ID] {
		t.Fatal("idempotency record was not saved after a successful usecase execution")
	}
	if usecase.gotCtx.Value(ctxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the usecase")
	}
	if idempotency.markedCtx.Value(ctxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validEvent()
	idempotency := newFakeIdempotencyRepository()
	idempotency.processed[event.ID] = true
	usecase := &fakeUsecase{}
	handler := postcreated.New(idempotency, usecase, testLogger())

	if err := handler.Handle(context.Background(), newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if usecase.calls != 0 {
		t.Fatalf("usecase Execute() calls = %d, want 0 for a duplicate event", usecase.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	usecase := &fakeUsecase{}
	handler := postcreated.New(idempotency, usecase, testLogger())

	event := validEvent()
	event.Description = "a distributed systems post"
	event.PostTags = []string{"go", "rabbitmq"}

	if err := handler.Handle(context.Background(), newMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if usecase.gotIn.Description != event.Description {
		t.Fatalf("usecase Execute() Description = %q, want %q", usecase.gotIn.Description, event.Description)
	}
	if len(usecase.gotIn.Tags) != 2 || usecase.gotIn.Tags[0] != "go" || usecase.gotIn.Tags[1] != "rabbitmq" {
		t.Fatalf("usecase Execute() Tags = %v, want [go rabbitmq]", usecase.gotIn.Tags)
	}
}

func TestHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	usecase := &fakeUsecase{}
	handler := postcreated.New(idempotency, usecase, testLogger())

	msg := message.NewMessage("1", []byte(`not json`))

	err := handler.Handle(context.Background(), msg)
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a malformed payload")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error so it skips retries")
	}
	if usecase.calls != 0 {
		t.Fatalf("usecase Execute() calls = %d, want 0 for a malformed payload", usecase.calls)
	}
}

func TestHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeIdempotencyRepository()
	usecase := &fakeUsecase{}
	handler := postcreated.New(idempotency, usecase, testLogger())

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
	usecase := &fakeUsecase{execErr: errors.New("mongo write failed")}
	handler := postcreated.New(idempotency, usecase, testLogger())

	err := handler.Handle(context.Background(), newMessage(t, validEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the usecase fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validEvent() postcreated.Event {
	return postcreated.Event{
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

func newMessage(t *testing.T, event postcreated.Event) *message.Message {
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
