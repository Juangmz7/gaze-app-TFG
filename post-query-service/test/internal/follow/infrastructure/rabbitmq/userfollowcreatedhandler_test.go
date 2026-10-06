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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakeUserFollowCreatedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakeUserFollowCreatedIdempotencyRepository() *fakeUserFollowCreatedIdempotencyRepository {
	return &fakeUserFollowCreatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserFollowCreatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeUserFollowCreatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakeUserFollowCreatedUsecase struct {
	calls   int
	gotIn   usecase.CreateFollowInput
	execErr error
}

func (f *fakeUserFollowCreatedUsecase) Execute(ctx context.Context, input usecase.CreateFollowInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalUserFollowCreatedEvent builds the real Java-shaped camelCase JSON
// payload for UserFollowedEvent as a literal map, per the task's CRITICAL
// instruction: new handlers must be proven against real Java field names,
// not a round-tripped Go struct.
func literalUserFollowCreatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":             uuid.New().String(),
		"correlationId":  uuid.New().String(),
		"occurredAt":     time.Now().UTC().Format(time.RFC3339Nano),
		"followerUserId": uuid.New().String(),
		"followedUserId": uuid.New().String(),
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

func TestUserFollowCreatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakeUserFollowCreatedIdempotencyRepository()
	uc := &fakeUserFollowCreatedUsecase{}
	handler := rabbitmq.NewUserFollowCreatedHandler(idempotency, uc, testUserFollowCreatedLogger())

	followerID := uuid.New()
	followedID := uuid.New()
	payload := literalUserFollowCreatedEvent(t, map[string]any{
		"followerUserId": followerID.String(),
		"followedUserId": followedID.String(),
	})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.FollowerUserID != followerID {
		t.Fatalf("uc Execute() FollowerUserID = %v, want %v", uc.gotIn.FollowerUserID, followerID)
	}
	if uc.gotIn.FollowedUserID != followedID {
		t.Fatalf("uc Execute() FollowedUserID = %v, want %v", uc.gotIn.FollowedUserID, followedID)
	}
}

func TestUserFollowCreatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakeUserFollowCreatedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakeUserFollowCreatedUsecase{}
	handler := rabbitmq.NewUserFollowCreatedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowCreatedEvent(t, map[string]any{"id": eventID.String()})

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

func TestUserFollowCreatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserFollowCreatedIdempotencyRepository()
	uc := &fakeUserFollowCreatedUsecase{}
	handler := rabbitmq.NewUserFollowCreatedHandler(idempotency, uc, testUserFollowCreatedLogger())

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

func TestUserFollowCreatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserFollowCreatedIdempotencyRepository()
	uc := &fakeUserFollowCreatedUsecase{}
	handler := rabbitmq.NewUserFollowCreatedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowCreatedEvent(t, map[string]any{"followedUserId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing followedUserId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserFollowCreatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserFollowCreatedIdempotencyRepository()
	uc := &fakeUserFollowCreatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserFollowCreatedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowCreatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testUserFollowCreatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
