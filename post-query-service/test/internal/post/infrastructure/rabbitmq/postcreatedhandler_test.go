package rabbitmq_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// literalPostCreatedJSON is the exact JSON the Java publisher emits. It is deliberately
// a literal, never a marshalled handler struct: marshalling the handler's own
// struct is what hid the snake_case contract bug.
const literalPostCreatedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","collabId":null,"postType":"BASIC","description":"Sunset over the harbour","postTags":["travel","sunset"],"media":[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/sunset.jpg","thumbnailUrl":null,"mediaType":"IMAGE","taggedUsers":["ana_rivera"],"order":1},{"id":"88888888-8888-4888-8888-888888888888","url":"https://media.example.test/harbour.mp4","thumbnailUrl":"https://media.example.test/harbour-thumb.jpg","mediaType":"VIDEO","duration":15000,"taggedUsers":[],"order":2}],"createdAt":"2026-03-01T10:15:00.5Z","updatedAt":"2026-03-01T10:15:20Z"}`

const (
	postCreatedPostID = "33333333-3333-4333-8333-333333333333"
	postCreatedUserID = "44444444-4444-4444-8444-444444444444"
)

type fakePostCreatedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markCalls      int
	markErr        error
	markedID       uuid.UUID
	markedCorrelID uuid.UUID
	isProcessedCtx context.Context
	markedCtx      context.Context
}

func newFakePostCreatedIdempotencyRepository() *fakePostCreatedIdempotencyRepository {
	return &fakePostCreatedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostCreatedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakePostCreatedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.markedID = eventID
	f.markedCorrelID = correlationID
	return f.markErr
}

type fakePostCreatedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.CreatePostInput
	execErr error
}

func (f *fakePostCreatedUsecase) Execute(ctx context.Context, input usecase.CreatePostInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

func TestPostCreatedHandler_Handle_DecodesTheLiteralJavaPayloadAndProjectsIt(t *testing.T) {
	idempotency := newFakePostCreatedIdempotencyRepository()
	uc := &fakePostCreatedUsecase{}
	handler := rabbitmq.NewPostCreatedHandler(idempotency, uc, testPostCreatedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
	}

	if uc.calls != 1 {
		t.Fatalf("Execute() calls = %d, want 1", uc.calls)
	}
	got := uc.gotIn
	if got.PostID != uuid.MustParse(postCreatedPostID) || got.UserID != uuid.MustParse(postCreatedUserID) {
		t.Fatalf("Execute() PostID/UserID = %v/%v", got.PostID, got.UserID)
	}
	if got.CollabID != uuid.Nil {
		t.Fatalf("Execute() CollabID = %v, want nil for a null collabId", got.CollabID)
	}
	if got.PostType != "BASIC" || got.Description != "Sunset over the harbour" {
		t.Fatalf("Execute() PostType/Description = %q/%q", got.PostType, got.Description)
	}
	if len(got.Tags) != 2 || got.Tags[0] != "travel" || got.Tags[1] != "sunset" {
		t.Fatalf("Execute() Tags = %v, want the postTags of the event", got.Tags)
	}
	if got.CreatedAt.IsZero() || got.UpdatedAt.IsZero() {
		t.Fatalf("Execute() CreatedAt/UpdatedAt = %v/%v, want both decoded", got.CreatedAt, got.UpdatedAt)
	}
	assertPostCreatedMedia(t, got.Media)
	if idempotency.markedID != uuid.MustParse("11111111-1111-4111-8111-111111111111") || idempotency.markedCorrelID != uuid.MustParse("22222222-2222-4222-8222-222222222222") {
		t.Fatalf("MarkProcessed() id/correlationId = %v/%v", idempotency.markedID, idempotency.markedCorrelID)
	}
}

func TestPostCreatedHandler_Handle_RejectsASnakeCasePayloadAsPermanent(t *testing.T) {
	uc := &fakePostCreatedUsecase{}
	handler := rabbitmq.NewPostCreatedHandler(newFakePostCreatedIdempotencyRepository(), uc, testPostCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","post_id":"33333333-3333-4333-8333-333333333333","user_id":"44444444-4444-4444-8444-444444444444","collab_id":"66666666-6666-4666-8666-666666666666","comment_id":"55555555-5555-4555-8555-555555555555","username":"ana_rivera","owner_user_id":"44444444-4444-4444-8444-444444444444","created_at":"2026-03-01T10:15:30Z"}`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error because the Java publishers emit camelCase", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0", uc.calls)
	}
}

func TestPostCreatedHandler_Handle_PropagatesContextToTheUsecaseAndIdempotencyRepository(t *testing.T) {
	type contextKey struct{}
	idempotency := newFakePostCreatedIdempotencyRepository()
	uc := &fakePostCreatedUsecase{}
	handler := rabbitmq.NewPostCreatedHandler(idempotency, uc, testPostCreatedLogger())
	ctx := context.WithValue(context.Background(), contextKey{}, "trace-value")

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), []byte(literalPostCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if uc.gotCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the usecase")
	}
	if idempotency.markedCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestPostCreatedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	idempotency := newFakePostCreatedIdempotencyRepository()
	idempotency.processed[uuid.MustParse("11111111-1111-4111-8111-111111111111")] = true
	uc := &fakePostCreatedUsecase{}
	handler := rabbitmq.NewPostCreatedHandler(idempotency, uc, testPostCreatedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestPostCreatedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	uc := &fakePostCreatedUsecase{}
	handler := rabbitmq.NewPostCreatedHandler(newFakePostCreatedIdempotencyRepository(), uc, testPostCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`not json`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a malformed payload", uc.calls)
	}
}

func TestPostCreatedHandler_Handle_ReturnsAPermanentErrorWhenARequiredFieldIsMissing(t *testing.T) {
	for _, field := range []string{"id", "correlationId", "occurredAt", "postId", "userId"} {
		t.Run(field, func(t *testing.T) {
			uc := &fakePostCreatedUsecase{}
			handler := rabbitmq.NewPostCreatedHandler(newFakePostCreatedIdempotencyRepository(), uc, testPostCreatedLogger())

			payload := mutatedPostCreatedPayload(t, func(fields map[string]any) { delete(fields, field) })

			err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error without %s", err, field)
			}
			if uc.calls != 0 {
				t.Fatalf("Execute() calls = %d, want 0", uc.calls)
			}
		})
	}
}

func TestPostCreatedHandler_Handle_ReturnsATransientErrorAndDoesNotMarkWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostCreatedIdempotencyRepository()
	uc := &fakePostCreatedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostCreatedHandler(idempotency, uc, testPostCreatedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCreatedJSON)))
	if err == nil {
		t.Fatal("Handle() error = nil, want an error when the usecase fails")
	}
	if rmqerror.IsPermanent(err) {
		t.Fatal("Handle() error is permanent, want a transient error so retries apply")
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 when the usecase failed", idempotency.markCalls)
	}
}

func assertPostCreatedMedia(t *testing.T, media []usecase.PostMedia) {
	t.Helper()

	if len(media) != 2 {
		t.Fatalf("Media length = %d, want 2", len(media))
	}
	image, video := media[0], media[1]
	if image.ID != uuid.MustParse("77777777-7777-4777-8777-777777777777") || image.URL != "https://media.example.test/sunset.jpg" {
		t.Fatalf("Media[0] ID/URL = %v/%q", image.ID, image.URL)
	}
	if image.ThumbnailURL != nil {
		t.Fatalf("Media[0] ThumbnailURL = %q, want nil for a null thumbnailUrl", *image.ThumbnailURL)
	}
	if image.MediaType != "IMAGE" || image.Order != 1 {
		t.Fatalf("Media[0] MediaType/Order = %q/%d", image.MediaType, image.Order)
	}
	if image.Duration != nil {
		t.Fatalf("Media[0] Duration = %d, want nil because the field is omitted for an IMAGE", *image.Duration)
	}
	if len(image.TaggedUsers) != 1 || image.TaggedUsers[0] != "ana_rivera" {
		t.Fatalf("Media[0] TaggedUsers = %v", image.TaggedUsers)
	}
	if video.ThumbnailURL == nil || *video.ThumbnailURL != "https://media.example.test/harbour-thumb.jpg" {
		t.Fatalf("Media[1] ThumbnailURL = %v", video.ThumbnailURL)
	}
	if video.MediaType != "VIDEO" || video.Order != 2 {
		t.Fatalf("Media[1] MediaType/Order = %q/%d", video.MediaType, video.Order)
	}
	if video.Duration == nil || *video.Duration != 15000 {
		t.Fatalf("Media[1] Duration = %v, want 15000 unchanged", video.Duration)
	}
}

func TestPostCreatedHandler_Handle_ReturnsAPermanentErrorForInvalidMedia(t *testing.T) {
	tests := []struct {
		name  string
		media string
	}{
		{name: "unknown media type", media: `[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/a.gif","mediaType":"GIF","order":1}]`},
		{name: "zero-based order", media: `[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/a.jpg","mediaType":"IMAGE","order":0}]`},
		{name: "missing url", media: `[{"id":"77777777-7777-4777-8777-777777777777","mediaType":"IMAGE","order":1}]`},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			uc := &fakePostCreatedUsecase{}
			handler := rabbitmq.NewPostCreatedHandler(newFakePostCreatedIdempotencyRepository(), uc, testPostCreatedLogger())

			payload := mutatedPostCreatedPayload(t, func(fields map[string]any) {
				var media any
				if err := json.Unmarshal([]byte(tt.media), &media); err != nil {
					t.Fatalf("json.Unmarshal() error = %v", err)
				}
				fields["media"] = media
			})

			err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error", err)
			}
			if uc.calls != 0 {
				t.Fatalf("Execute() calls = %d, want 0", uc.calls)
			}
		})
	}
}

// literalPostCreatedEvent returns the Java-shaped JSON with fresh ids, for tests
// that deliver several distinct events.
func literalPostCreatedEvent(t *testing.T) []byte {
	t.Helper()

	return mutatedPostCreatedPayload(t, func(fields map[string]any) {
		fields["id"] = uuid.NewString()
		fields["postId"] = uuid.NewString()
	})
}

// mutatedPostCreatedPayload starts from the literal Java JSON and lets a test change
// individual fields.
func mutatedPostCreatedPayload(t *testing.T, mutate func(fields map[string]any)) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal([]byte(literalPostCreatedJSON), &fields); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	mutate(fields)

	payload, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return payload
}

func testPostCreatedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
