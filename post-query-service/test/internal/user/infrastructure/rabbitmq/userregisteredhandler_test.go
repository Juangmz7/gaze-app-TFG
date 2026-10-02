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

type fakeUserRegisteredIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakeUserRegisteredIdempotencyRepository() *fakeUserRegisteredIdempotencyRepository {
	return &fakeUserRegisteredIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserRegisteredIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakeUserRegisteredIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type fakeUserRegisteredUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RegisterUserInput
	execErr error
}

func (f *fakeUserRegisteredUsecase) Execute(ctx context.Context, input usecase.RegisterUserInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type userRegisteredCtxKey struct{}

func TestUserRegisteredHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	event := validUserRegisteredEvent()
	ctx := context.WithValue(context.Background(), userRegisteredCtxKey{}, "trace-value")

	if err := handler.Handle(ctx, newUserRegisteredMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.UserID != event.UserID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, event.UserID)
	}
	if uc.gotIn.Username != event.Username {
		t.Fatalf("uc Execute() Username = %q, want %q", uc.gotIn.Username, event.Username)
	}
	if !idempotency.processed[event.ID] {
		t.Fatal("idempotency record was not saved after a successful uc execution")
	}
	if uc.gotCtx.Value(userRegisteredCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the uc")
	}
	if idempotency.markedCtx.Value(userRegisteredCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestUserRegisteredHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validUserRegisteredEvent()
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	idempotency.processed[event.ID] = true
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	if err := handler.Handle(context.Background(), newUserRegisteredMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestUserRegisteredHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	event := validUserRegisteredEvent()
	event.Username = "distributed-systems-fan"

	if err := handler.Handle(context.Background(), newUserRegisteredMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.Username != event.Username {
		t.Fatalf("uc Execute() Username = %q, want %q", uc.gotIn.Username, event.Username)
	}
}

func TestUserRegisteredHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

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

func TestUserRegisteredHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	event := validUserRegisteredEvent()
	event.Username = ""

	err := handler.Handle(context.Background(), newUserRegisteredMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing username")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserRegisteredHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserRegisteredIdempotencyRepository()
	uc := &fakeUserRegisteredUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserRegisteredHandler(idempotency, uc, testUserRegisteredLogger())

	err := handler.Handle(context.Background(), newUserRegisteredMessage(t, validUserRegisteredEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validUserRegisteredEvent() rabbitmq.UserRegisteredEvent {
	return rabbitmq.UserRegisteredEvent{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
		Username:      "ada-lovelace",
		CreatedAt:     time.Now().UTC(),
	}
}

func newUserRegisteredMessage(t *testing.T, event rabbitmq.UserRegisteredEvent) *message.Message {
	t.Helper()

	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return message.NewMessage(uuid.NewString(), payload)
}

func testUserRegisteredLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
