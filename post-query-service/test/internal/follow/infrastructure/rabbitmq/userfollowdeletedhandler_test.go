package rabbitmq_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type fakeUserFollowDeletedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakeUserFollowDeletedIdempotencyRepository() *fakeUserFollowDeletedIdempotencyRepository {
	return &fakeUserFollowDeletedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserFollowDeletedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeUserFollowDeletedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakeUserFollowDeletedUsecase struct {
	calls   int
	gotIn   usecase.DeleteFollowInput
	execErr error
}

func (f *fakeUserFollowDeletedUsecase) Execute(ctx context.Context, input usecase.DeleteFollowInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

func literalUserFollowDeletedEvent(t *testing.T, overrides map[string]any) []byte {
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

func TestUserFollowDeletedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakeUserFollowDeletedIdempotencyRepository()
	uc := &fakeUserFollowDeletedUsecase{}
	handler := rabbitmq.NewUserFollowDeletedHandler(idempotency, uc, testUserFollowCreatedLogger())

	followerID := uuid.New()
	followedID := uuid.New()
	payload := literalUserFollowDeletedEvent(t, map[string]any{
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

func TestUserFollowDeletedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakeUserFollowDeletedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakeUserFollowDeletedUsecase{}
	handler := rabbitmq.NewUserFollowDeletedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowDeletedEvent(t, map[string]any{"id": eventID.String()})

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

func TestUserFollowDeletedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserFollowDeletedIdempotencyRepository()
	uc := &fakeUserFollowDeletedUsecase{}
	handler := rabbitmq.NewUserFollowDeletedHandler(idempotency, uc, testUserFollowCreatedLogger())

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

func TestUserFollowDeletedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserFollowDeletedIdempotencyRepository()
	uc := &fakeUserFollowDeletedUsecase{}
	handler := rabbitmq.NewUserFollowDeletedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowDeletedEvent(t, map[string]any{"followedUserId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing followedUserId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserFollowDeletedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserFollowDeletedIdempotencyRepository()
	uc := &fakeUserFollowDeletedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserFollowDeletedHandler(idempotency, uc, testUserFollowCreatedLogger())

	payload := literalUserFollowDeletedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}
