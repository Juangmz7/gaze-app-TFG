package rabbitmq_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/infrastructure/rabbitmq"
)

// literalPostViewedJSON is the exact JSON post-command-service's PostViewedEvent record
// produces (Jackson, camelCase, Instants as ISO-8601). A literal, never a marshalled
// handler struct.
const literalPostViewedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:30.123456Z","viewId":"55555555-5555-4555-8555-555555555555","postId":"33333333-3333-4333-8333-333333333333","userId":"44444444-4444-4444-8444-444444444444","source":"HOME_FEED","feedPosition":3,"durationMs":15000,"timeWatchedMs":9000,"completionPercent":60,"exitReason":"SCROLL_NEXT","serverTimestamp":"2026-03-01T10:15:30.123456Z","replayCount":2}`

type fakePostViewedIdempotency struct {
	processed map[uuid.UUID]bool
	markCalls int
	markedID  uuid.UUID
	markedCtx context.Context
}

func newFakePostViewedIdempotency() *fakePostViewedIdempotency {
	return &fakePostViewedIdempotency{processed: map[uuid.UUID]bool{}}
}

func (f *fakePostViewedIdempotency) IsProcessed(_ context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakePostViewedIdempotency) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.markCalls++
	f.markedID = eventID
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type historyKey struct{ userID, postID uuid.UUID }

// fakeHistoryStore stands in for view_history behind the real usecase: one row per
// (user, post) holding the newest view, the contract HistoryRepository.Record meets on
// MongoDB (tested against a real one in the mongo package).
type fakeHistoryStore struct {
	rows    map[historyKey]time.Time
	records int
	err     error
	gotCtx  context.Context
}

func (f *fakeHistoryStore) Record(ctx context.Context, input usecase.RecordPostViewInput) error {
	f.gotCtx = ctx
	if f.err != nil {
		return f.err
	}
	f.records++
	key := historyKey{input.UserID, input.PostID}
	if stored, found := f.rows[key]; !found || input.ViewedAt.After(stored) {
		f.rows[key] = input.ViewedAt
	}
	return nil
}

// fakeViewCounter stands in for the posts repository's AdjustViewCount: counts per
// post, and the context of the last call.
type fakeViewCounter struct {
	counts map[uuid.UUID]int64
	err    error
	gotCtx context.Context
}

func newFakeViewCounter() *fakeViewCounter {
	return &fakeViewCounter{counts: map[uuid.UUID]int64{}}
}

func (f *fakeViewCounter) AdjustViewCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.gotCtx = ctx
	if f.err != nil {
		return false, f.err
	}
	f.counts[postID] += delta
	return true, nil
}

type postViewedTransactionKey struct{}

// fakePostViewedTransactor marks the context it hands to fn, the way a real
// transaction carries its session.
type fakePostViewedTransactor struct{}

func (fakePostViewedTransactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(context.WithValue(ctx, postViewedTransactionKey{}, true))
}

func newPostViewedHandler(idempotency rabbitmq.PostViewedIdempotencyRepository, store *fakeHistoryStore) *rabbitmq.PostViewedHandler {
	return newCountingPostViewedHandler(idempotency, store, newFakeViewCounter())
}

func newCountingPostViewedHandler(idempotency rabbitmq.PostViewedIdempotencyRepository, store *fakeHistoryStore, counter *fakeViewCounter) *rabbitmq.PostViewedHandler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return rabbitmq.NewPostViewedHandler(fakePostViewedTransactor{}, idempotency, usecase.NewRecordPostView(store, counter, logger), logger)
}

func viewPayload(t *testing.T, mutate func(fields map[string]any)) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal([]byte(literalPostViewedJSON), &fields); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	mutate(fields)
	payload, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	return payload
}

func TestPostViewedHandler_Handle_UpsertsOneRowPerUserAndPostAndKeepsTheNewestTime(t *testing.T) {
	store := &fakeHistoryStore{rows: map[historyKey]time.Time{}}
	handler := newPostViewedHandler(newFakePostViewedIdempotency(), store)
	viewer, other := uuid.New(), uuid.New()
	postID := uuid.New()
	base := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	// Ten views by the viewer, the newest delivered before two older ones; one view by
	// another user of the same post.
	for _, minute := range []int{0, 1, 2, 3, 4, 5, 6, 9, 7, 8} {
		payload := viewPayload(t, func(fields map[string]any) {
			fields["id"], fields["viewId"] = uuid.NewString(), uuid.NewString()
			fields["postId"], fields["userId"] = postID.String(), viewer.String()
			fields["serverTimestamp"] = base.Add(time.Duration(minute) * time.Minute).Format(time.RFC3339Nano)
			// occurredAt is not the view's time: the history must use serverTimestamp.
			fields["occurredAt"] = base.Add(time.Hour).Format(time.RFC3339Nano)
		})
		if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}
	otherView := viewPayload(t, func(fields map[string]any) {
		fields["id"], fields["postId"], fields["userId"] = uuid.NewString(), postID.String(), other.String()
	})
	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), otherView)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if len(store.rows) != 2 {
		t.Fatalf("history rows = %d, want one per user and post (2)", len(store.rows))
	}
	if got := store.rows[historyKey{viewer, postID}]; !got.Equal(base.Add(9 * time.Minute)) {
		t.Fatalf("viewer's last view = %v, want the newest serverTimestamp %v", got, base.Add(9*time.Minute))
	}
	if got := store.rows[historyKey{other, postID}]; !got.Equal(time.Date(2026, 3, 1, 10, 15, 30, 123456000, time.UTC)) {
		t.Fatalf("other user's last view = %v, want their own serverTimestamp", got)
	}
}

func TestPostViewedHandler_Handle_DecodesTheLiteralJavaPayloadInsideOneTransaction(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	store := &fakeHistoryStore{rows: map[historyKey]time.Time{}}
	handler := newPostViewedHandler(idempotency, store)

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
	}

	key := historyKey{uuid.MustParse("44444444-4444-4444-8444-444444444444"), uuid.MustParse("33333333-3333-4333-8333-333333333333")}
	if got, found := store.rows[key]; !found || !got.Equal(time.Date(2026, 3, 1, 10, 15, 30, 123456000, time.UTC)) {
		t.Fatalf("rows = %v, want the viewer's row at the serverTimestamp", store.rows)
	}
	if idempotency.markedID != uuid.MustParse("11111111-1111-4111-8111-111111111111") {
		t.Fatalf("MarkProcessed() id = %v, want the event id", idempotency.markedID)
	}
	if store.gotCtx.Value(postViewedTransactionKey{}) != true || idempotency.markedCtx.Value(postViewedTransactionKey{}) != true {
		t.Fatal("the history write and MarkProcessed must run inside the transaction")
	}
}

func TestPostViewedHandler_Handle_SkipsADuplicateAndAcks(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	idempotency.processed[uuid.MustParse("11111111-1111-4111-8111-111111111111")] = true
	store := &fakeHistoryStore{rows: map[historyKey]time.Time{}}
	handler := newPostViewedHandler(idempotency, store)

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON))); err != nil {
		t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
	}

	if store.records != 0 || idempotency.markCalls != 0 {
		t.Fatalf("records = %d, marks = %d, want none for a duplicate", store.records, idempotency.markCalls)
	}
}

func TestPostViewedHandler_Handle_ReturnsAPermanentErrorForAnInvalidPayload(t *testing.T) {
	cases := map[string][]byte{
		"not json":   []byte(`not json`),
		"snake_case": []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","post_id":"33333333-3333-4333-8333-333333333333","user_id":"44444444-4444-4444-8444-444444444444","server_timestamp":"2026-03-01T10:15:30Z"}`),
		"bad postId": viewPayload(t, func(fields map[string]any) { fields["postId"] = "not-a-uuid" }),
	}
	for _, field := range []string{"id", "correlationId", "occurredAt", "postId", "userId", "serverTimestamp"} {
		cases["without "+field] = viewPayload(t, func(fields map[string]any) { delete(fields, field) })
	}

	for name, payload := range cases {
		t.Run(name, func(t *testing.T) {
			idempotency := newFakePostViewedIdempotency()
			store := &fakeHistoryStore{rows: map[historyKey]time.Time{}}
			handler := newPostViewedHandler(idempotency, store)

			err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
			}
			if store.records != 0 || idempotency.markCalls != 0 {
				t.Fatalf("records = %d, marks = %d, want none", store.records, idempotency.markCalls)
			}
		})
	}
}

func TestPostViewedHandler_Handle_ReturnsARetryableErrorAndDoesNotMarkWhenTheWriteFails(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	store := &fakeHistoryStore{rows: map[historyKey]time.Time{}, err: fmt.Errorf("insert: %w", errors.New("duplicate key from a concurrent first view"))}
	handler := newPostViewedHandler(idempotency, store)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON)))
	if err == nil || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a retryable error", err)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 when the write failed", idempotency.markCalls)
	}
}

func TestPostViewedHandler_Handle_IncrementsTheViewCountByOneInsideTheTransaction(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	counter := newFakeViewCounter()
	handler := newCountingPostViewedHandler(idempotency, &fakeHistoryStore{rows: map[historyKey]time.Time{}}, counter)

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	postID := uuid.MustParse("33333333-3333-4333-8333-333333333333")
	if counter.counts[postID] != 1 || len(counter.counts) != 1 {
		t.Fatalf("view counts = %v, want exactly 1 on post %s", counter.counts, postID)
	}
	if counter.gotCtx.Value(postViewedTransactionKey{}) != true {
		t.Fatal("the view count must be adjusted inside the transaction")
	}
}

func TestPostViewedHandler_Handle_DoesNotIncrementTheViewCountWhenTheEventWasAlreadyProcessed(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	counter := newFakeViewCounter()
	handler := newCountingPostViewedHandler(idempotency, &fakeHistoryStore{rows: map[historyKey]time.Time{}}, counter)

	// The same delivery twice: the second finds the event processed.
	for range 2 {
		if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON))); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
	}

	if got := counter.counts[uuid.MustParse("33333333-3333-4333-8333-333333333333")]; got != 1 {
		t.Fatalf("view count = %d, want 1 for one event delivered twice", got)
	}
}

func TestPostViewedHandler_Handle_ReturnsTheErrorAndDoesNotMarkTheEventProcessedWhenTheViewCountUpdateFails(t *testing.T) {
	idempotency := newFakePostViewedIdempotency()
	counter := newFakeViewCounter()
	counter.err = errors.New("write conflict")
	handler := newCountingPostViewedHandler(idempotency, &fakeHistoryStore{rows: map[historyKey]time.Time{}}, counter)

	err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostViewedJSON)))
	if err == nil || rmqerror.IsPermanent(err) {
		t.Fatalf("Handle() error = %v, want a retryable error", err)
	}
	if !errors.Is(err, counter.err) {
		t.Fatalf("Handle() error = %v, want it to wrap %v", err, counter.err)
	}
	if idempotency.markCalls != 0 {
		t.Fatalf("MarkProcessed() calls = %d, want 0 when the view count failed", idempotency.markCalls)
	}
}
