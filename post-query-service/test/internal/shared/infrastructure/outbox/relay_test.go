package outbox_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
)

type failedAttempt struct {
	id        uuid.UUID
	attempts  int
	status    outbox.Status
	lastError string
}

// fakeStore hands out queued events in order and records every mark call.
type fakeStore struct {
	queue      []outbox.Event
	processed  []uuid.UUID
	failed     []failedAttempt
	processErr error
}

func (s *fakeStore) ClaimNext(context.Context, time.Duration) (*outbox.Event, error) {
	if len(s.queue) == 0 {
		return nil, nil
	}
	event := s.queue[0]
	s.queue = s.queue[1:]
	event.Attempts++
	event.Status = outbox.StatusProcessing
	return &event, nil
}

func (s *fakeStore) ClaimIfPending(_ context.Context, id uuid.UUID) (*outbox.Event, error) {
	for i, event := range s.queue {
		if event.ID == id {
			s.queue = append(s.queue[:i:i], s.queue[i+1:]...)
			event.Attempts++
			event.Status = outbox.StatusProcessing
			return &event, nil
		}
	}
	return nil, nil
}

func (s *fakeStore) MarkProcessed(_ context.Context, id uuid.UUID) error {
	if s.processErr != nil {
		return s.processErr
	}
	s.processed = append(s.processed, id)
	return nil
}

func (s *fakeStore) MarkFailedAttempt(_ context.Context, id uuid.UUID, attempts int, status outbox.Status, lastError string) (bool, error) {
	s.failed = append(s.failed, failedAttempt{id: id, attempts: attempts, status: status, lastError: lastError})
	return true, nil
}

// fakePublisher fails for the event ids listed in failFor.
type fakePublisher struct {
	unavailable error
	failFor     map[uuid.UUID]error
	published   []uuid.UUID
}

func (p *fakePublisher) Ready(context.Context) error {
	return p.unavailable
}

func (p *fakePublisher) Publish(_ context.Context, event outbox.Event) error {
	if err, ok := p.failFor[event.ID]; ok {
		return err
	}
	p.published = append(p.published, event.ID)
	return nil
}

func newTestRelay(store outbox.RelayStore, publisher outbox.Publisher) *outbox.Relay {
	cfg := outbox.DefaultRelayConfig()
	return outbox.NewRelay(store, publisher, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func pendingEvent(attempts int) outbox.Event {
	return outbox.Event{ID: uuid.New(), EventType: "TestEvent", Status: outbox.StatusPending, Attempts: attempts}
}

func TestRelay_RelayBatch_MarksEveryConfirmedEventProcessed(t *testing.T) {
	first, second := pendingEvent(0), pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{first, second}}
	publisher := &fakePublisher{}

	if err := newTestRelay(store, publisher).RelayBatch(context.Background()); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	if len(store.processed) != 2 || store.processed[0] != first.ID || store.processed[1] != second.ID {
		t.Fatalf("processed = %v, want [%v %v] in order", store.processed, first.ID, second.ID)
	}
}

func TestRelay_RelayBatch_StopsAtFirstFailureAndReleasesEventAsPending(t *testing.T) {
	failing, next := pendingEvent(0), pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{failing, next}}
	publisher := &fakePublisher{failFor: map[uuid.UUID]error{failing.ID: errors.New("broker nacked")}}

	if err := newTestRelay(store, publisher).RelayBatch(context.Background()); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	if len(publisher.published) != 0 {
		t.Fatalf("published = %v, want nothing after the first failure", publisher.published)
	}
	if len(store.queue) != 1 || store.queue[0].ID != next.ID {
		t.Fatal("RelayBatch() claimed the next event after a failure, want the batch to stop")
	}
	// Released with the attempt of its own claim, so a stale relay cannot release a newer claim
	want := failedAttempt{id: failing.ID, attempts: 1, status: outbox.StatusPending, lastError: "broker nacked"}
	if len(store.failed) != 1 || store.failed[0] != want {
		t.Fatalf("failed = %+v, want [%+v]", store.failed, want)
	}
}

func TestRelay_RelayBatch_MovesEventToFailedOnceAttemptsAreExhausted(t *testing.T) {
	cfg := outbox.DefaultRelayConfig()
	exhausted := pendingEvent(cfg.MaxAttempts - 1) // the claim makes it MaxAttempts
	store := &fakeStore{queue: []outbox.Event{exhausted}}
	publisher := &fakePublisher{failFor: map[uuid.UUID]error{exhausted.ID: errors.New("unknown exchange")}}

	if err := newTestRelay(store, publisher).RelayBatch(context.Background()); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	if len(store.failed) != 1 || store.failed[0].status != outbox.StatusFailed {
		t.Fatalf("failed = %+v, want one FAILED release", store.failed)
	}
}

func TestRelay_RelayBatch_ReportsTheDuplicateAndStopsWhenMarkProcessedFails(t *testing.T) {
	published, next := pendingEvent(0), pendingEvent(0)
	dbErr := errors.New("mongo unavailable")
	store := &fakeStore{queue: []outbox.Event{published, next}, processErr: dbErr}
	publisher := &fakePublisher{}

	err := newTestRelay(store, publisher).RelayBatch(context.Background())

	if !errors.Is(err, dbErr) || !strings.Contains(err.Error(), "republished as a duplicate") {
		t.Fatalf("RelayBatch() error = %v, want the store error explaining the upcoming duplicate", err)
	}
	// Already delivered: it is not a failed attempt; the expired lock republishes it
	if len(store.failed) != 0 {
		t.Fatalf("failed = %+v, want no release for a delivered event", store.failed)
	}
	if len(store.queue) != 1 || store.queue[0].ID != next.ID {
		t.Fatal("RelayBatch() kept claiming after the store failed, want the batch to stop")
	}
}

func TestRelay_RelayBatch_ClaimsNothingWhileTheBrokerIsUnreachable(t *testing.T) {
	event := pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{event}}
	publisher := &fakePublisher{unavailable: errors.New("connection refused")}

	if err := newTestRelay(store, publisher).RelayBatch(context.Background()); err != nil {
		t.Fatalf("RelayBatch() error = %v", err)
	}

	if len(store.queue) != 1 || len(store.failed) != 0 {
		t.Fatalf("RelayBatch() claimed or released events while the broker was down: queue=%d failed=%v",
			len(store.queue), store.failed)
	}
}

func TestRelay_Start_StopsWhenContextIsCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	done := newTestRelay(&fakeStore{}, &fakePublisher{}).Start(ctx)

	cancel()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not stop after the context was cancelled")
	}
}

func TestRelay_RelayImmediately_PublishesOnlyTheCommittedEventAndMarksItProcessed(t *testing.T) {
	older, committed := pendingEvent(0), pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{older, committed}}
	publisher := &fakePublisher{}

	newTestRelay(store, publisher).RelayImmediately(context.Background(), committed.ID)

	if len(publisher.published) != 1 || publisher.published[0] != committed.ID {
		t.Fatalf("published = %v, want only [%v]", publisher.published, committed.ID)
	}
	if len(store.processed) != 1 || store.processed[0] != committed.ID {
		t.Fatalf("processed = %v, want [%v]", store.processed, committed.ID)
	}
	if len(store.queue) != 1 || store.queue[0].ID != older.ID {
		t.Fatal("RelayImmediately() claimed another event, want the older one left to the batch")
	}
}

func TestRelay_RelayImmediately_DoesNothingWhenABatchAlreadyClaimedTheEvent(t *testing.T) {
	store := &fakeStore{}
	publisher := &fakePublisher{}

	newTestRelay(store, publisher).RelayImmediately(context.Background(), uuid.New())

	if len(publisher.published) != 0 || len(store.processed) != 0 || len(store.failed) != 0 {
		t.Fatalf("published=%v processed=%v failed=%v, want nothing", publisher.published, store.processed, store.failed)
	}
}

func TestRelay_RelayImmediately_ReleasesAFailedPublishAsPendingForTheBatch(t *testing.T) {
	event := pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{event}}
	publisher := &fakePublisher{failFor: map[uuid.UUID]error{event.ID: errors.New("broker nacked")}}

	newTestRelay(store, publisher).RelayImmediately(context.Background(), event.ID)

	want := failedAttempt{id: event.ID, attempts: 1, status: outbox.StatusPending, lastError: "broker nacked"}
	if len(store.failed) != 1 || store.failed[0] != want {
		t.Fatalf("failed = %+v, want [%+v]", store.failed, want)
	}
}

func TestRelay_RelayImmediately_ClaimsNothingWhileTheBrokerIsUnreachable(t *testing.T) {
	event := pendingEvent(0)
	store := &fakeStore{queue: []outbox.Event{event}}
	publisher := &fakePublisher{unavailable: errors.New("connection refused")}

	newTestRelay(store, publisher).RelayImmediately(context.Background(), event.ID)

	if len(store.queue) != 1 || store.queue[0].Attempts != 0 {
		t.Fatalf("queue = %+v, want the event untouched for the batch", store.queue)
	}
}
