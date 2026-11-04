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

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// literalPostCollabOpenedJSON is the exact JSON the Java publisher emits. It is deliberately
// a literal, never a marshalled handler struct: marshalling the handler's own
// struct is what hid the snake_case contract bug.
const literalPostCollabOpenedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","collabId":"66666666-6666-4666-8666-666666666666","title":"Harbour series","createdBy":"44444444-4444-4444-8444-444444444444","collabStatus":"OPEN","collabCreatedAt":"2026-03-01T10:15:00.5Z","creatorMemberStatus":"ACCEPTED","creatorRole":"ADMIN","creatorMemberCreatedAt":"2026-03-01T10:15:00.5Z","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","postCollabId":"66666666-6666-4666-8666-666666666666","postType":"COLLAB","description":"Sunset over the harbour","postTags":["travel"],"media":[{"id":"77777777-7777-4777-8777-777777777777","url":"https://media.example.test/sunset.jpg","thumbnailUrl":null,"mediaType":"IMAGE","taggedUsers":["ana_rivera"],"order":1},{"id":"88888888-8888-4888-8888-888888888888","url":"https://media.example.test/harbour.mp4","thumbnailUrl":"https://media.example.test/harbour-thumb.jpg","mediaType":"VIDEO","duration":15000,"taggedUsers":[],"order":2}],"postCreatedAt":"2026-03-01T10:15:00.5Z","postUpdatedAt":"2026-03-01T10:15:00.5Z","withPostCreated":true}`

type fakePostCollabOpenedIdempotencyRepository struct {
	processed      map[uuid.UUID]bool
	markCalls      int
	markErr        error
	markedID       uuid.UUID
	markedCorrelID uuid.UUID
	isProcessedCtx context.Context
	markedCtx      context.Context
}

func newFakePostCollabOpenedIdempotencyRepository() *fakePostCollabOpenedIdempotencyRepository {
	return &fakePostCollabOpenedIdempotencyRepository{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostCollabOpenedIdempotencyRepository) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.isProcessedCtx = ctx
	return f.processed[eventID], nil
}

func (f *fakePostCollabOpenedIdempotencyRepository) MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, _ string) error {
	f.markCalls++
	f.markedCtx = ctx
	f.markedID = eventID
	f.markedCorrelID = correlationID
	return f.markErr
}

type fakePostCollabOpenedUsecase struct {
	calls   int
	gotCtx  context.Context
	gotIn   usecase.RecordCollabOpenedInput
	execErr error
}

func (f *fakePostCollabOpenedUsecase) Execute(ctx context.Context, input usecase.RecordCollabOpenedInput) error {
	f.calls++
	f.gotCtx = ctx
	f.gotIn = input
	return f.execErr
}

func TestPostCollabOpenedHandler_Handle_DecodesTheLiteralJavaPayloadAndProjectsIt(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCollabOpenedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
	}

	if uc.calls != 1 {
		t.Fatalf("Execute() calls = %d, want 1", uc.calls)
	}
	got := uc.gotIn
	if got.CollabID != uuid.MustParse("66666666-6666-4666-8666-666666666666") || got.PostID != uuid.MustParse("33333333-3333-4333-8333-333333333333") {
		t.Fatalf("Execute() CollabID/PostID = %v/%v", got.CollabID, got.PostID)
	}
	if got.OwnerUserID != uuid.MustParse("44444444-4444-4444-8444-444444444444") {
		t.Fatalf("Execute() OwnerUserID = %v, want the event's createdBy", got.OwnerUserID)
	}
	if got.Title != "Harbour series" || got.Status != "OPEN" {
		t.Fatalf("Execute() Title/Status = %q/%q", got.Title, got.Status)
	}
	if got.CreatedAt.IsZero() {
		t.Fatal("Execute() CreatedAt is zero, want the event's collabCreatedAt")
	}
	if idempotency.markedID != uuid.MustParse("11111111-1111-4111-8111-111111111111") || idempotency.markedCorrelID != uuid.MustParse("22222222-2222-4222-8222-222222222222") {
		t.Fatalf("MarkProcessed() id/correlationId = %v/%v", idempotency.markedID, idempotency.markedCorrelID)
	}
}

func TestPostCollabOpenedHandler_Handle_RejectsASnakeCasePayloadAsPermanent(t *testing.T) {
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(newFakePostCollabOpenedIdempotencyRepository(), uc, testPostCollabOpenedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","post_id":"33333333-3333-4333-8333-333333333333","user_id":"44444444-4444-4444-8444-444444444444","collab_id":"66666666-6666-4666-8666-666666666666","comment_id":"55555555-5555-4555-8555-555555555555","username":"ana_rivera","owner_user_id":"44444444-4444-4444-8444-444444444444","created_at":"2026-03-01T10:15:30Z"}`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error because the Java publishers emit camelCase", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0", uc.calls)
	}
}

func TestPostCollabOpenedHandler_Handle_PropagatesContextToTheUsecaseAndIdempotencyRepository(t *testing.T) {
	type contextKey struct{}
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())
	ctx := context.WithValue(context.Background(), contextKey{}, "trace-value")

	if err := handler.Handle(ctx, message.NewMessage(uuid.NewString(), []byte(literalPostCollabOpenedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if uc.gotCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the usecase")
	}
	if idempotency.markedCtx.Value(contextKey{}) != "trace-value" {
		t.Fatal("context was not propagated from Handle() to the idempotency repository")
	}
}

func TestPostCollabOpenedHandler_Handle_SkipsUsecaseAndAcksWhenEventIsADuplicate(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	idempotency.processed[uuid.MustParse("11111111-1111-4111-8111-111111111111")] = true
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCollabOpenedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a duplicate event", uc.calls)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 for a duplicate event", idempotency.markCalls)
	}
}

func TestPostCollabOpenedHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	uc := &fakePostCollabOpenedUsecase{}
	handler := rabbitmq.NewPostCollabOpenedHandler(newFakePostCollabOpenedIdempotencyRepository(), uc, testPostCollabOpenedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(`not json`)))
	if !rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
	}
	if uc.calls != 0 {
		t.Fatalf("Execute() calls = %d, want 0 for a malformed payload", uc.calls)
	}
}

func TestPostCollabOpenedHandler_Handle_ReturnsAPermanentErrorWhenARequiredFieldIsMissing(t *testing.T) {
	for _, field := range []string{"id", "correlationId", "occurredAt", "collabId", "postId", "createdBy"} {
		t.Run(field, func(t *testing.T) {
			uc := &fakePostCollabOpenedUsecase{}
			handler := rabbitmq.NewPostCollabOpenedHandler(newFakePostCollabOpenedIdempotencyRepository(), uc, testPostCollabOpenedLogger())

			payload := mutatedPostCollabOpenedPayload(t, func(fields map[string]any) { delete(fields, field) })

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

func TestPostCollabOpenedHandler_Handle_ReturnsATransientErrorAndDoesNotMarkWhenTheUsecaseFails(t *testing.T) {
	idempotency := newFakePostCollabOpenedIdempotencyRepository()
	uc := &fakePostCollabOpenedUsecase{execErr: errors.New("mongo write failed")}
	handler := rabbitmq.NewPostCollabOpenedHandler(idempotency, uc, testPostCollabOpenedLogger())

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostCollabOpenedJSON)))
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

// mutatedPostCollabOpenedPayload starts from the literal Java JSON and lets a test change
// individual fields.
func mutatedPostCollabOpenedPayload(t *testing.T, mutate func(fields map[string]any)) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal([]byte(literalPostCollabOpenedJSON), &fields); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	mutate(fields)

	payload, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("json.Marshal() error = %v", err)
	}

	return payload
}

func testPostCollabOpenedLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
