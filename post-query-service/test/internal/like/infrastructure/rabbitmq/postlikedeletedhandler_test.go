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

type fakePostLikeDeletedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakePostLikeDeletedIdempotencyRepository() *fakePostLikeDeletedIdempotencyRepository {
	return &fakePostLikeDeletedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostLikeDeletedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostLikeDeletedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakePostLikeDeletedUsecase struct {
	calls   int
	gotIn   usecase.DeleteLikeInput
	execErr error
}

func (f *fakePostLikeDeletedUsecase) Execute(ctx context.Context, input usecase.DeleteLikeInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

func literalPostLikeDeletedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
		"postId":        uuid.New().String(),
		"userId":        uuid.New().String(),
		"source":        "HOME_FEED",
		"feedPosition":  3,
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

func TestPostLikeDeletedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakePostLikeDeletedIdempotencyRepository()
	uc := &fakePostLikeDeletedUsecase{}
	handler := rabbitmq.NewPostLikeDeletedHandler(idempotency, uc, testPostLikeDeletedLogger())

	postID := uuid.New()
	userID := uuid.New()
	payload := literalPostLikeDeletedEvent(t, map[string]any{"postId": postID.String(), "userId": userID.String()})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.PostID != postID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, postID)
	}
	if uc.gotIn.UserID != userID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, userID)
	}
}

func TestPostLikeDeletedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakePostLikeDeletedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakePostLikeDeletedUsecase{}
	handler := rabbitmq.NewPostLikeDeletedHandler(idempotency, uc, testPostLikeDeletedLogger())

	payload := literalPostLikeDeletedEvent(t, map[string]any{"id": eventID.String()})

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

func TestPostLikeDeletedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostLikeDeletedIdempotencyRepository()
	uc := &fakePostLikeDeletedUsecase{}
	handler := rabbitmq.NewPostLikeDeletedHandler(idempotency, uc, testPostLikeDeletedLogger())

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

func TestPostLikeDeletedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostLikeDeletedIdempotencyRepository()
	uc := &fakePostLikeDeletedUsecase{}
	handler := rabbitmq.NewPostLikeDeletedHandler(idempotency, uc, testPostLikeDeletedLogger())

	payload := literalPostLikeDeletedEvent(t, map[string]any{"userId": uuid.Nil.String()})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing userId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostLikeDeletedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostLikeDeletedIdempotencyRepository()
	uc := &fakePostLikeDeletedUsecase{execErr: errors.New("mongo delete failed")}
	handler := rabbitmq.NewPostLikeDeletedHandler(idempotency, uc, testPostLikeDeletedLogger())

	payload := literalPostLikeDeletedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testPostLikeDeletedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
