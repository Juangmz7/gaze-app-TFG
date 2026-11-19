package rabbitmq_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// Literal JSON of the Java records PostCommentLikeCreatedEvent and
// PostCommentLikeDeletedEvent (Jackson, camelCase, ISO-8601 instants). Never a
// marshalled handler struct.
const (
	literalCommentLikeCreatedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","postId":"33333333-3333-4333-8333-333333333333","commentId":"55555555-5555-4555-8555-555555555555","userId":"44444444-4444-4444-8444-444444444444","source":"HOME_FEED","feedPosition":3,"createdAt":"2026-03-01T10:15:30.1Z"}`
	literalCommentLikeDeletedJSON = `{"id":"66666666-6666-4666-8666-666666666666","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:16:30Z","commentId":"55555555-5555-4555-8555-555555555555","userId":"44444444-4444-4444-8444-444444444444","source":"HOME_FEED","feedPosition":3}`
)

func discardCommentLikeLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// fakeCommentLikeIdempotency records processed events in memory.
type fakeCommentLikeIdempotency struct {
	processed map[uuid.UUID]bool
	markErr   error
	inTx      []bool
}

func newFakeCommentLikeIdempotency() *fakeCommentLikeIdempotency {
	return &fakeCommentLikeIdempotency{processed: map[uuid.UUID]bool{}}
}

func (f *fakeCommentLikeIdempotency) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.inTx = append(f.inTx, ctx.Value(commentLikeTransactionKey{}) == true)
	return f.processed[eventID], nil
}

func (f *fakeCommentLikeIdempotency) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.inTx = append(f.inTx, ctx.Value(commentLikeTransactionKey{}) == true)
	if f.markErr != nil {
		return f.markErr
	}
	f.processed[eventID] = true
	return nil
}

type commentLikeTransactionKey struct{}

type fakeCommentLikeTransactor struct{}

func (fakeCommentLikeTransactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(context.WithValue(ctx, commentLikeTransactionKey{}, true))
}

type fakeRecordCommentLike struct {
	inputs []usecase.RecordCommentLikeInput
	inTx   bool
	err    error
}

func (f *fakeRecordCommentLike) Execute(ctx context.Context, input usecase.RecordCommentLikeInput) error {
	f.inputs = append(f.inputs, input)
	f.inTx = ctx.Value(commentLikeTransactionKey{}) == true
	return f.err
}

type fakeDeleteCommentLike struct {
	inputs []usecase.DeleteCommentLikeInput
	err    error
}

func (f *fakeDeleteCommentLike) Execute(_ context.Context, input usecase.DeleteCommentLikeInput) error {
	f.inputs = append(f.inputs, input)
	return f.err
}

func deliver(payload string) *message.Message {
	return message.NewMessage(uuid.NewString(), []byte(payload))
}

func TestPostCommentLikeCreatedHandler_Handle_ProjectsTheLiteralJavaPayloadOnceInOneTransaction(t *testing.T) {
	idempotency, uc := newFakeCommentLikeIdempotency(), &fakeRecordCommentLike{}
	handler := rabbitmq.NewPostCommentLikeCreatedHandler(fakeCommentLikeTransactor{}, idempotency, uc, discardCommentLikeLogger())

	for range 2 {
		if err := handler.Handle(context.Background(), deliver(literalCommentLikeCreatedJSON)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}

	if len(uc.inputs) != 1 {
		t.Fatalf("usecase calls = %d, want 1: the redelivery is a duplicate", len(uc.inputs))
	}
	got := uc.inputs[0]
	if got.CommentID.String() != "55555555-5555-4555-8555-555555555555" || got.PostID.String() != "33333333-3333-4333-8333-333333333333" ||
		got.UserID.String() != "44444444-4444-4444-8444-444444444444" || got.CreatedAt.IsZero() {
		t.Fatalf("input = %+v", got)
	}
	if !uc.inTx || len(idempotency.inTx) == 0 || containsFalse(idempotency.inTx) {
		t.Fatalf("usecase in tx = %v, idempotency in tx = %v, want everything in one transaction", uc.inTx, idempotency.inTx)
	}
}

func containsFalse(values []bool) bool {
	for _, value := range values {
		if !value {
			return true
		}
	}
	return false
}

func TestPostCommentLikeDeletedHandler_Handle_ProjectsTheLiteralJavaPayloadOnce(t *testing.T) {
	idempotency, uc := newFakeCommentLikeIdempotency(), &fakeDeleteCommentLike{}
	handler := rabbitmq.NewPostCommentLikeDeletedHandler(fakeCommentLikeTransactor{}, idempotency, uc, discardCommentLikeLogger())

	for range 2 {
		if err := handler.Handle(context.Background(), deliver(literalCommentLikeDeletedJSON)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}

	if len(uc.inputs) != 1 {
		t.Fatalf("usecase calls = %d, want 1", len(uc.inputs))
	}
	if got := uc.inputs[0]; got.CommentID.String() != "55555555-5555-4555-8555-555555555555" || got.UserID.String() != "44444444-4444-4444-8444-444444444444" ||
		got.CorrelationID.String() != "22222222-2222-4222-8222-222222222222" {
		t.Fatalf("input = %+v", got)
	}
	if containsFalse(idempotency.inTx) {
		t.Fatalf("idempotency in tx = %v", idempotency.inTx)
	}
}

func TestPostCommentLikeHandlers_Handle_RejectInvalidPayloadsAsPermanent(t *testing.T) {
	created := rabbitmq.NewPostCommentLikeCreatedHandler(fakeCommentLikeTransactor{}, newFakeCommentLikeIdempotency(), &fakeRecordCommentLike{}, discardCommentLikeLogger())
	deleted := rabbitmq.NewPostCommentLikeDeletedHandler(fakeCommentLikeTransactor{}, newFakeCommentLikeIdempotency(), &fakeDeleteCommentLike{}, discardCommentLikeLogger())
	cases := map[string]struct {
		handle  func(context.Context, *message.Message) error
		payload string
	}{
		"created: not json":         {created.Handle, `not json`},
		"created: snake case":       {created.Handle, strings.ReplaceAll(strings.ReplaceAll(literalCommentLikeCreatedJSON, "commentId", "comment_id"), "correlationId", "correlation_id")},
		"created: no comment id":    {created.Handle, strings.Replace(literalCommentLikeCreatedJSON, `"commentId":"55555555-5555-4555-8555-555555555555",`, "", 1)},
		"created: no user id":       {created.Handle, strings.Replace(literalCommentLikeCreatedJSON, `"userId":"44444444-4444-4444-8444-444444444444",`, "", 1)},
		"created: no occurredAt":    {created.Handle, strings.Replace(literalCommentLikeCreatedJSON, `"occurredAt":"2026-03-01T10:15:30.123456Z",`, "", 1)},
		"deleted: not json":         {deleted.Handle, `{`},
		"deleted: no comment id":    {deleted.Handle, strings.Replace(literalCommentLikeDeletedJSON, `"commentId":"55555555-5555-4555-8555-555555555555",`, "", 1)},
		"deleted: no id":            {deleted.Handle, strings.Replace(literalCommentLikeDeletedJSON, `"id":"66666666-6666-4666-8666-666666666666",`, "", 1)},
		"deleted: no correlationId": {deleted.Handle, strings.Replace(literalCommentLikeDeletedJSON, `"correlationId":"22222222-2222-4222-8222-222222222222",`, "", 1)},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			if err := tc.handle(context.Background(), deliver(tc.payload)); !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error", err)
			}
		})
	}
}

func TestPostCommentLikeHandlers_Handle_RetryAndDoNotMarkWhenTheProjectionFails(t *testing.T) {
	wantErr := errors.New("mongo down")

	createdMarks := newFakeCommentLikeIdempotency()
	created := rabbitmq.NewPostCommentLikeCreatedHandler(fakeCommentLikeTransactor{}, createdMarks, &fakeRecordCommentLike{err: wantErr}, discardCommentLikeLogger())
	if err := created.Handle(context.Background(), deliver(literalCommentLikeCreatedJSON)); !errors.Is(err, wantErr) || rmqerror.IsPermanent(err) {
		t.Fatalf("created Handle() error = %v, want a retryable %v", err, wantErr)
	}
	deletedMarks := newFakeCommentLikeIdempotency()
	deleted := rabbitmq.NewPostCommentLikeDeletedHandler(fakeCommentLikeTransactor{}, deletedMarks, &fakeDeleteCommentLike{err: wantErr}, discardCommentLikeLogger())
	if err := deleted.Handle(context.Background(), deliver(literalCommentLikeDeletedJSON)); !errors.Is(err, wantErr) || rmqerror.IsPermanent(err) {
		t.Fatalf("deleted Handle() error = %v, want a retryable %v", err, wantErr)
	}
	if len(createdMarks.processed)+len(deletedMarks.processed) != 0 {
		t.Fatal("an event was marked processed although its projection failed")
	}

	failingMark := newFakeCommentLikeIdempotency()
	failingMark.markErr = wantErr
	created = rabbitmq.NewPostCommentLikeCreatedHandler(fakeCommentLikeTransactor{}, failingMark, &fakeRecordCommentLike{}, discardCommentLikeLogger())
	if err := created.Handle(context.Background(), deliver(literalCommentLikeCreatedJSON)); !errors.Is(err, wantErr) || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a retryable failure of MarkProcessed", err)
	}
}
