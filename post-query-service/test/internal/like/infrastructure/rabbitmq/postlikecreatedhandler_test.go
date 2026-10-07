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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakePostLikeCreatedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markedCtx      context.Context
	markCalls      int
	isProcessedCtx context.Context
}

func newFakePostLikeCreatedIdempotencyRepository() *fakePostLikeCreatedIdempotencyRepository {
	return &fakePostLikeCreatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostLikeCreatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakePostLikeCreatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type fakePostLikeCreatedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RecordLikeInput
	execErr error
}

func (f *fakePostLikeCreatedUsecase) Execute(ctx context.Context, input usecase.RecordLikeInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

type postLikeCreatedCtxKey struct{}

func TestPostLikeCreatedHandler_Handle_CallsUsecaseAndMarksProcessedForANewValidEvent(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

	event := validPostLikeCreatedEvent()
	ctx := context.WithValue(context.Background(), postLikeCreatedCtxKey{}, "trace-value")

	if err := handler.Handle(ctx, newPostLikeCreatedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.LikeID != event.LikeID {
		t.Fatalf("uc Execute() LikeID = %v, want %v", uc.gotIn.LikeID, event.LikeID)
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
	if uc.gotCtx.Value(postLikeCreatedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the uc")
	}
	if idempotency.markedCtx.Value(postLikeCreatedCtxKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestPostLikeCreatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	event := validPostLikeCreatedEvent()
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	idempotency.processed[event.ID] = true
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

	if err := handler.Handle(context.Background(), newPostLikeCreatedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("uc Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestPostLikeCreatedHandler_Handle_MapsPayloadFieldsBeforeCallingUsecase(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

	event := validPostLikeCreatedEvent()

	if err := handler.Handle(context.Background(), newPostLikeCreatedMessage(t, event)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.gotIn.CreatedAt.Unix() != event.CreatedAt.Unix() {
		t.Fatalf("uc Execute() CreatedAt = %v, want %v", uc.gotIn.CreatedAt, event.CreatedAt)
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

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

func TestPostLikeCreatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

	event := validPostLikeCreatedEvent()
	event.LikeID = uuid.Nil

	err := handler.Handle(context.Background(), newPostLikeCreatedMessage(t, event))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing like_id")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostLikeCreatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostLikeCreatedIdempotencyRepository()
	uc := &fakePostLikeCreatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostLikeCreatedHandler(idempotency, uc, testPostLikeCreatedLogger())

	err := handler.Handle(context.Background(), newPostLikeCreatedMessage(t, validPostLikeCreatedEvent()))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func validPostLikeCreatedEvent() rabbitmq.PostLikeCreatedEvent {
	return rabbitmq.PostLikeCreatedEvent{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		LikeID:        uuid.New(),
		PostID:        uuid.New(),
		UserID:        uuid.New(),
		CreatedAt:     time.Now().UTC(),
	}
}

func newPostLikeCreatedMessage(t *testing.T, event rabbitmq.PostLikeCreatedEvent) *message.Message {
	t.Helper()

	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return message.NewMessage(uuid.NewString(), payload)
}

func testPostLikeCreatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
