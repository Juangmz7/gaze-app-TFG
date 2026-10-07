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

type fakeUserUpdatedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakeUserUpdatedIdempotencyRepository() *fakeUserUpdatedIdempotencyRepository {
	return &fakeUserUpdatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakeUserUpdatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeUserUpdatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakeUserUpdatedUsecase struct {
	calls   int
	gotIn   usecase.UpdateUserInput
	execErr error
}

func (f *fakeUserUpdatedUsecase) Execute(ctx context.Context, input usecase.UpdateUserInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalUserUpdatedEvent builds the real Java-shaped camelCase JSON
// payload for UserUpdatedEvent as a literal map, per the task's CRITICAL
// instruction: new handlers must be proven against real Java field names,
// not a round-tripped Go struct.
func literalUserUpdatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
		"userId":        uuid.New().String(),
		"username":      "ada-lovelace-renamed",
		"email":         "ada@example.com",
		"bio": map[string]any{
			"description": "mathematician",
			"socialMedia": map[string]string{"twitter": "@ada"},
		},
		"pictureUrl":    "https://example.com/ada.png",
		"accountStatus": "ACCEPTED",
		"createdAt":     time.Now().UTC().Format(time.RFC3339Nano),
		"updatedAt":     time.Now().UTC().Format(time.RFC3339Nano),
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

func TestUserUpdatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakeUserUpdatedIdempotencyRepository()
	uc := &fakeUserUpdatedUsecase{}
	handler := rabbitmq.NewUserUpdatedHandler(idempotency, uc, testUserUpdatedLogger())

	userID := uuid.New()
	payload := literalUserUpdatedEvent(t, map[string]any{"userId": userID.String(), "username": "grace-hopper"})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.UserID != userID {
		t.Fatalf("uc Execute() UserID = %v, want %v", uc.gotIn.UserID, userID)
	}
	if uc.gotIn.Username != "grace-hopper" {
		t.Fatalf("uc Execute() Username = %q, want %q", uc.gotIn.Username, "grace-hopper")
	}
	if uc.gotIn.BioDescription != "mathematician" {
		t.Fatalf("uc Execute() BioDescription = %q, want %q", uc.gotIn.BioDescription, "mathematician")
	}
	if uc.gotIn.BioSocialMedia["twitter"] != "@ada" {
		t.Fatalf("uc Execute() BioSocialMedia[twitter] = %q, want %q", uc.gotIn.BioSocialMedia["twitter"], "@ada")
	}
}

func TestUserUpdatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakeUserUpdatedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakeUserUpdatedUsecase{}
	handler := rabbitmq.NewUserUpdatedHandler(idempotency, uc, testUserUpdatedLogger())

	payload := literalUserUpdatedEvent(t, map[string]any{"id": eventID.String()})

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

func TestUserUpdatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakeUserUpdatedIdempotencyRepository()
	uc := &fakeUserUpdatedUsecase{}
	handler := rabbitmq.NewUserUpdatedHandler(idempotency, uc, testUserUpdatedLogger())

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

func TestUserUpdatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakeUserUpdatedIdempotencyRepository()
	uc := &fakeUserUpdatedUsecase{}
	handler := rabbitmq.NewUserUpdatedHandler(idempotency, uc, testUserUpdatedLogger())

	payload := literalUserUpdatedEvent(t, map[string]any{"username": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing username")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestUserUpdatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakeUserUpdatedIdempotencyRepository()
	uc := &fakeUserUpdatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewUserUpdatedHandler(idempotency, uc, testUserUpdatedLogger())

	payload := literalUserUpdatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testUserUpdatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
