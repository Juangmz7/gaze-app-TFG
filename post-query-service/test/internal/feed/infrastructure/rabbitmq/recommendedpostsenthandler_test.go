package rabbitmq_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"

	"github.com/ThreeDotsLabs/watermill"
	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

type contextKey struct{}

type fakeIdempotency struct {
	processed map[uuid.UUID]bool
	marked    []uuid.UUID
	isErr     error
	markErr   error
	gotValues []any
}

func (f *fakeIdempotency) IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error) {
	f.gotValues = append(f.gotValues, ctx.Value(contextKey{}))
	return f.processed[eventID], f.isErr
}

func (f *fakeIdempotency) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.gotValues = append(f.gotValues, ctx.Value(contextKey{}))
	f.marked = append(f.marked, eventID)
	return f.markErr
}

type fakeBuildUserFeed struct {
	inputs   []usecase.BuildUserFeedInput
	gotValue any
	err      error
}

func (f *fakeBuildUserFeed) Execute(ctx context.Context, input usecase.BuildUserFeedInput) (usecase.BuildUserFeedOutput, error) {
	f.inputs = append(f.inputs, input)
	f.gotValue = ctx.Value(contextKey{})
	return usecase.BuildUserFeedOutput{}, f.err
}

func newHandler(idempotency *fakeIdempotency, uc *fakeBuildUserFeed) *feedrabbitmq.RecommendedPostSentHandler {
	return feedrabbitmq.NewRecommendedPostSentHandler(idempotency, uc, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// literalPayload is what pydantic's RecommendedPostSentEvent produces, with its
// microseconds and its Z.
func literalPayload(id, correlationID, userID string, posts string) []byte {
	return fmt.Appendf(nil, `{"id":"%s","correlationId":"%s","occurredAt":"2026-11-05T10:15:30.547912Z","userId":"%s","posts":%s}`, id, correlationID, userID, posts)
}

func newMessage(payload []byte) *message.Message {
	return message.NewMessage(watermill.NewUUID(), payload)
}

func TestRecommendedPostSentHandler_Handle_DecodesTheRealContractCallsTheUsecaseAndMarksTheEvent(t *testing.T) {
	idempotency := &fakeIdempotency{processed: map[uuid.UUID]bool{}}
	uc := &fakeBuildUserFeed{}
	id, token, userID, first, second := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	payload := literalPayload(id.String(), token.String(), userID.String(), fmt.Sprintf(`["%s","%s"]`, first, second))

	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")
	if err := newHandler(idempotency, uc).Handle(ctx, newMessage(payload)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if len(uc.inputs) != 1 {
		t.Fatalf("usecase calls = %d, want 1", len(uc.inputs))
	}
	input := uc.inputs[0]
	if input.UserID != userID || input.LeaseToken != token || len(input.PostIDs) != 2 || input.PostIDs[0] != first || input.PostIDs[1] != second {
		t.Fatalf("input = %+v", input)
	}
	if len(idempotency.marked) != 1 || idempotency.marked[0] != id {
		t.Fatalf("marked = %v, want [%v]", idempotency.marked, id)
	}
	if uc.gotValue != "propagated" {
		t.Fatal("the usecase did not get the handler's context")
	}
	for _, value := range idempotency.gotValues {
		if value != "propagated" {
			t.Fatal("the idempotency repository did not get the handler's context")
		}
	}
}

func TestRecommendedPostSentHandler_Handle_AcceptsAnEmptyPostList(t *testing.T) {
	idempotency := &fakeIdempotency{processed: map[uuid.UUID]bool{}}
	uc := &fakeBuildUserFeed{}
	payload := literalPayload(uuid.NewString(), uuid.NewString(), uuid.NewString(), `[]`)

	if err := newHandler(idempotency, uc).Handle(context.Background(), newMessage(payload)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if len(uc.inputs) != 1 || len(idempotency.marked) != 1 {
		t.Fatalf("usecase calls = %d, marked = %d; want the lease released and the event marked", len(uc.inputs), len(idempotency.marked))
	}
}

func TestRecommendedPostSentHandler_Handle_AcksADuplicateWithoutCallingTheUsecase(t *testing.T) {
	id := uuid.New()
	idempotency := &fakeIdempotency{processed: map[uuid.UUID]bool{id: true}}
	uc := &fakeBuildUserFeed{}
	var logs bytes.Buffer
	handler := feedrabbitmq.NewRecommendedPostSentHandler(idempotency, uc, slog.New(slog.NewTextHandler(&logs, nil)))
	token, userID := uuid.New(), uuid.New()

	if err := handler.Handle(context.Background(), newMessage(literalPayload(id.String(), token.String(), userID.String(), `[]`))); err != nil {
		t.Fatalf("Handle() error = %v, want a duplicate acknowledged", err)
	}

	if len(uc.inputs) != 0 || len(idempotency.marked) != 0 {
		t.Fatalf("usecase calls = %d, marked = %d; want neither", len(uc.inputs), len(idempotency.marked))
	}
	for _, want := range []string{"duplicate", "correlation_id=" + token.String(), "userId=" + userID.String()} {
		if !bytes.Contains(logs.Bytes(), []byte(want)) {
			t.Fatalf("logs = %s, want %s", &logs, want)
		}
	}
}

func TestRecommendedPostSentHandler_Handle_ReturnsAPermanentErrorForAMalformedPayload(t *testing.T) {
	id, token, userID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	tests := []struct {
		name    string
		payload []byte
	}{
		{"not json", []byte(`{not json`)},
		{"post id is not a uuid", literalPayload(id, token, userID, `["not-a-uuid"]`)},
		{"posts is not a list", literalPayload(id, token, userID, `"post"`)},
		{"posts is null", literalPayload(id, token, userID, `null`)},
		{"posts is missing", fmt.Appendf(nil, `{"id":"%s","correlationId":"%s","occurredAt":"2026-11-05T10:15:30Z","userId":"%s"}`, id, token, userID)},
		{"id is missing", fmt.Appendf(nil, `{"correlationId":"%s","occurredAt":"2026-11-05T10:15:30Z","userId":"%s","posts":[]}`, token, userID)},
		{"correlationId is missing", fmt.Appendf(nil, `{"id":"%s","occurredAt":"2026-11-05T10:15:30Z","userId":"%s","posts":[]}`, id, userID)},
		{"occurredAt is missing", fmt.Appendf(nil, `{"id":"%s","correlationId":"%s","userId":"%s","posts":[]}`, id, token, userID)},
		{"userId is missing", fmt.Appendf(nil, `{"id":"%s","correlationId":"%s","occurredAt":"2026-11-05T10:15:30Z","posts":[]}`, id, token)},
		{"snake case", fmt.Appendf(nil, `{"id":"%s","correlation_id":"%s","occurred_at":"2026-11-05T10:15:30Z","user_id":"%s","posts":[]}`, id, token, userID)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			idempotency := &fakeIdempotency{processed: map[uuid.UUID]bool{}}
			uc := &fakeBuildUserFeed{}

			err := newHandler(idempotency, uc).Handle(context.Background(), newMessage(tt.payload))

			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent one", err)
			}
			if len(uc.inputs) != 0 || len(idempotency.marked) != 0 {
				t.Fatal("processed a malformed payload")
			}
		})
	}
}

func TestRecommendedPostSentHandler_Handle_ReturnsATransientErrorAndDoesNotMarkWhenTheUsecaseFails(t *testing.T) {
	wantErr := errors.New("mongo failed")
	idempotency := &fakeIdempotency{processed: map[uuid.UUID]bool{}}
	payload := literalPayload(uuid.NewString(), uuid.NewString(), uuid.NewString(), `[]`)

	err := newHandler(idempotency, &fakeBuildUserFeed{err: wantErr}).Handle(context.Background(), newMessage(payload))

	if !errors.Is(err, wantErr) || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a transient one wrapping %v", err, wantErr)
	}
	if len(idempotency.marked) != 0 {
		t.Fatal("marked an event whose usecase failed")
	}
}

func TestRecommendedPostSentHandler_Handle_ReturnsATransientErrorWhenTheIdempotencyCheckFails(t *testing.T) {
	wantErr := errors.New("mongo failed")
	uc := &fakeBuildUserFeed{}
	payload := literalPayload(uuid.NewString(), uuid.NewString(), uuid.NewString(), `[]`)

	err := newHandler(&fakeIdempotency{isErr: wantErr}, uc).Handle(context.Background(), newMessage(payload))

	if !errors.Is(err, wantErr) || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a transient one wrapping %v", err, wantErr)
	}
	if len(uc.inputs) != 0 {
		t.Fatal("called the usecase without an idempotency answer")
	}
}
