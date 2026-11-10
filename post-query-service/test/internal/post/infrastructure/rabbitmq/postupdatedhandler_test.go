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

type fakePostUpdatedIdempotencyRepository struct {
	processed map[uuid.UUID]bool
	markCalls int
}

func newFakePostUpdatedIdempotencyRepository() *fakePostUpdatedIdempotencyRepository {
	return &fakePostUpdatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostUpdatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostUpdatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.processed[eventID] = true
	return nil
}

type fakePostUpdatedUsecase struct {
	calls   int
	gotIn   usecase.UpdatePostInput
	execErr error
}

func (f *fakePostUpdatedUsecase) Execute(ctx context.Context, input usecase.UpdatePostInput) error {
	f.calls++
	f.gotIn = input
	return f.execErr
}

// literalPostUpdatedEvent builds the real Java-shaped camelCase JSON
// payload for PostUpdatedEvent as a literal map, per the task's requirement
// that new handlers be proven against real Java field names, not a
// round-tripped Go struct.
func literalPostUpdatedEvent(t *testing.T, overrides map[string]any) []byte {
	t.Helper()

	payload := map[string]any{
		"id":            uuid.New().String(),
		"correlationId": uuid.New().String(),
		"occurredAt":    time.Now().UTC().Format(time.RFC3339Nano),
		"postId":        uuid.New().String(),
		"userId":        uuid.New().String(),
		"collabId":      uuid.New().String(),
		"postType":      "TEXT",
		"description":   "updated description",
		"postTags":      []string{"go", "rabbitmq"},
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

func TestPostUpdatedHandler_Handle_DecodesCamelCaseJSONAndCallsUsecase(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	postID := uuid.New()
	payload := literalPostUpdatedEvent(t, map[string]any{"postId": postID.String(), "description": "new description"})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v, want nil", err)
	}

	if uc.calls != 1 {
		t.Fatalf("uc Execute() calls = %d, want 1", uc.calls)
	}
	if uc.gotIn.PostID != postID {
		t.Fatalf("uc Execute() PostID = %v, want %v", uc.gotIn.PostID, postID)
	}
	if uc.gotIn.Description != "new description" {
		t.Fatalf("uc Execute() Description = %q, want %q", uc.gotIn.Description, "new description")
	}
}

func TestPostUpdatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	eventID := uuid.New()
	idempotency := newFakePostUpdatedIdempotencyRepository()
	idempotency.processed[eventID] = true
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, map[string]any{"id": eventID.String()})

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

func TestPostUpdatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

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

func TestPostUpdatedHandler_Handle_ReturnsAPermanentErrorWhenRequiredFieldsAreMissing(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, map[string]any{"postId": ""})

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error for a missing postId")
	}
	if !rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is not permanent, want a permanent error for an invalid envelope")
	}
}

func TestPostUpdatedHandler_Handle_ReturnsATransientErrorWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostUpdatedIdempotencyRepository()
	uc := &fakePostUpdatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostUpdatedHandler(idempotency, uc, testPostUpdatedLogger())

	payload := literalPostUpdatedEvent(t, nil)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the uc fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
}

func testPostUpdatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// literalPostUpdatedWithMedia is the exact JSON the Java publisher emits.
const literalPostUpdatedWithMedia = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","collabId":null,"postType":"BASIC","description":"Edited caption","postTags":["travel"],"media":[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/sunset.jpg","thumbnailUrl":null,"mediaType":"IMAGE","taggedUsers":["ana_rivera"],"order":1},{"id":"88888888-8888-4888-8888-888888888888","url":"https://media.example.test/harbour.mp4","thumbnailUrl":"https://media.example.test/harbour-thumb.jpg","mediaType":"VIDEO","duration":15000,"taggedUsers":[],"order":2}],"createdAt":"2026-03-01T10:15:00.5Z","updatedAt":"2026-03-01T10:15:20Z"}`

func TestPostUpdatedHandler_Handle_DecodesMediaFromTheLiteralJavaPayload(t *testing.T) {
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(newFakePostUpdatedIdempotencyRepository(), uc, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostUpdatedWithMedia))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	media := uc.gotIn.Media
	if len(media) != 2 {
		t.Fatalf("Media length = %d, want 2", len(media))
	}
	if media[0].ID != uuid.MustParse("77777777-7777-4777-8777-777777777777") || media[0].URL != "https://media.example.test/sunset.jpg" || media[0].MediaType != "IMAGE" || media[0].Order != 1 {
		t.Fatalf("Media[0] = %+v", media[0])
	}
	if media[0].ThumbnailURL != nil || media[0].Duration != nil {
		t.Fatalf("Media[0] ThumbnailURL/Duration = %v/%v, want nil for a null thumbnail and an omitted duration", media[0].ThumbnailURL, media[0].Duration)
	}
	if media[1].Duration == nil || *media[1].Duration != 15000 {
		t.Fatalf("Media[1] Duration = %v, want 15000 unchanged", media[1].Duration)
	}
	if media[1].ThumbnailURL == nil || *media[1].ThumbnailURL != "https://media.example.test/harbour-thumb.jpg" {
		t.Fatalf("Media[1] ThumbnailURL = %v", media[1].ThumbnailURL)
	}
}

func TestPostUpdatedHandler_Handle_ReturnsAPermanentErrorForInvalidMedia(t *testing.T) {
	uc := &fakePostUpdatedUsecase{}
	handler := rabbitmq.NewPostUpdatedHandler(newFakePostUpdatedIdempotencyRepository(), uc, slog.New(slog.NewTextHandler(io.Discard, nil)))

	payload := `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","media":[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/a.gif","mediaType":"GIF","order":1}]}`

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(payload)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0", uc.calls)
	}
}
