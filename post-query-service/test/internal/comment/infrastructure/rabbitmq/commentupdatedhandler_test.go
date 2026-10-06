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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakeCommentUpdatedUsecase struct {
	calls   int
	gotIn   usecase.UpdateCommentInput
	execErr error
}

func (f *fakeCommentUpdatedUsecase) Execute(ctx context.Context, input usecase.UpdateCommentInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalCommentUpdatedEvent builds the real Java-shaped camelCase JSON
// payload for CommentUpdatedEvent as a literal map. Note there is no "id",
// "correlationId", or "occurredAt" field: the real Java CommentUpdatedEvent
// does not implement EventMessage (see CommentUpdatedEvent's doc comment).
func literalCommentUpdatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"commentId": uuid.New().String(),
		"postId":    uuid.New().String(),
		"userId":    uuid.New().String(),
		"content":   "edited comment",
		"replyTo":   nil,
		"createdAt": time.Now().UTC().Format(time.RFC3339Nano),
		"updatedAt": time.Now().UTC().Format(time.RFC3339Nano),
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

func TestCommentUpdatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	uc := &fakeCommentUpdatedUsecase{}
	handler := rabbitmq.NewCommentUpdatedHandler(uc, testCommentUpdatedLogger())

	commentID := uuid.New()
	payload := literalCommentUpdatedEvent(t, map[string]any{"commentId": commentID.String(), "content": "new content"})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.CommentID != commentID {
		t.Fatalf("uc Execute() CommentID = %v, want %v", uc.gotIn.CommentID, commentID)
	}
	if uc.gotIn.Content != "new content" {
		t.Fatalf("uc Execute() Content = %q, want %q", uc.gotIn.Content, "new content")
	}
}

// TestCommentUpdatedHandler_Handle_RedeliveringTheSameUpdateTwiceDoesNotError
// proves the compensating behavior documented on CommentUpdatedHandler:
// since there is no idempotency gate, a duplicate delivery must rely on the
// usecase's own safety net, not on the handler skipping it. With a fake
// usecase that always succeeds (mirroring an idempotent Update), handling
// the same payload twice must not error either time.
func TestCommentUpdatedHandler_Handle_RedeliveringTheSameUpdateTwiceDoesNotError(t *testing.T) {
	uc := &fakeCommentUpdatedUsecase{}
	handler := rabbitmq.NewCommentUpdatedHandler(uc, testCommentUpdatedLogger())

	payload := literalCommentUpdatedEvent(t, nil)

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() first delivery error = %v, want nil", err)
	}
	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() redelivery error = %v, want nil", err)
	}
	if uc.calls != 2 {
		t.Fatalf("uc Execute() calls = %d, want 2 (no idempotency gate at the handler level)", uc.calls)
	}
}

func TestCommentUpdatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	uc := &fakeCommentUpdatedUsecase{}
	handler := rabbitmq.NewCommentUpdatedHandler(uc, testCommentUpdatedLogger())

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

func TestCommentUpdatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	uc := &fakeCommentUpdatedUsecase{}
	handler := rabbitmq.NewCommentUpdatedHandler(uc, testCommentUpdatedLogger())

	payload := literalCommentUpdatedEvent(t, map[string]any{"commentId": uuid.Nil.String()})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing commentId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestCommentUpdatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	uc := &fakeCommentUpdatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewCommentUpdatedHandler(uc, testCommentUpdatedLogger())

	payload := literalCommentUpdatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testCommentUpdatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
