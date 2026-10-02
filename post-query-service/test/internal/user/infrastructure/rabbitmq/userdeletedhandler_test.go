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

type fakeUserDeletedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakeUserDeletedIdempotencyRepository() *fakeUserDeletedIdempotencyRepository {
	return &fakeUserDeletedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserDeletedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakeUserDeletedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type fakeUserDeletedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.DeleteUserInput
	execErr error
}

func (f *fakeUserDeletedUsecase) Execute(ctx context.Context, input usecase.DeleteUserInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type userDeletedCtxKey struct{}

func TestUserDeletedHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakeUserDeletedIdempotencyRepository()
	uc := &fakeUserDeletedUsecase{}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

	event := validUserDeletedEvent()
	ctx := context.WithValue(context.Background(), userDeletedCtxKey{}, "trace-value")

	if err := handler.Handle(ctx, newUserDeletedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.UserID != event.UserID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, event.UserID)
	}
	if !idempotency.processed[event.ID] {
		t.Fatal("idempotency record was not saved after a successful uc execution")
	}
	if uc.gotCtx.Value(userDeletedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the uc")
	}
	if idempotency.markedCtx.Value(userDeletedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestUserDeletedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validUserDeletedEvent()
	idempotency := newFakeUserDeletedIdempotencyRepository()
	idempotency.processed[event.ID] = true
	uc := &fakeUserDeletedUsecase{}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

	if err := handler.Handle(context.Background(), newUserDeletedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestUserDeletedHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakeUserDeletedIdempotencyRepository()
	uc := &fakeUserDeletedUsecase{}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

	event := validUserDeletedEvent()

	if err := handler.Handle(context.Background(), newUserDeletedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.UserID != event.UserID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, event.UserID)
	}
}

func TestUserDeletedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserDeletedIdempotencyRepository()
	uc := &fakeUserDeletedUsecase{}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

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

func TestUserDeletedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserDeletedIdempotencyRepository()
	uc := &fakeUserDeletedUsecase{}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

	event := validUserDeletedEvent()
	event.UserID = uuid.Nil

	err := handler.Handle(context.Background(), newUserDeletedMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing user_id")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserDeletedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserDeletedIdempotencyRepository()
	uc := &fakeUserDeletedUsecase{execErr: errors.New("mongo delete failed")}
	handler := rabbitmq.NewUserDeletedHandler(idempotency, uc, testUserDeletedLogger())

	err := handler.Handle(context.Background(), newUserDeletedMessage(t, validUserDeletedEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validUserDeletedEvent() rabbitmq.UserDeletedEvent {
	return rabbitmq.UserDeletedEvent{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
	}
}

func newUserDeletedMessage(t *testing.T, event rabbitmq.UserDeletedEvent) *message.Message {
	t.Helper()

	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return message.NewMessage(uuid.NewString(), payload)
}

func testUserDeletedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
