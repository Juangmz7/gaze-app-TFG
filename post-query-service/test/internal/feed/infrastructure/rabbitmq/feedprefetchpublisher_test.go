package rabbitmq_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
)

type fakeEventPublisher struct {
	events []publisher.Event
	err    error
}

func (f *fakeEventPublisher) Publish(_ context.Context, event publisher.Event) error {
	f.events = append(f.events, event)
	return f.err
}

func TestFeedPrefetchPublisher_PublishFeedPrefetch_MapsTheRequestToTheEvent(t *testing.T) {
	events := &fakeEventPublisher{}
	request := usecase.FeedPrefetchRequest{
		CorrelationID:   uuid.New(),
		UserID:          uuid.New(),
		Reason:          usecase.FeedPrefetchReasonStaleHeartbeat,
		CurrentFeedSize: 4,
		ExcludedPostIDs: []uuid.UUID{uuid.New()},
	}

	if err := feedrabbitmq.NewFeedPrefetchPublisher(events).PublishFeedPrefetch(context.Background(), request); err != nil {
		t.Fatalf("PublishFeedPrefetch() error = %v", err)
	}

	event := events.events[0]
	if event.ID == uuid.Nil || event.OccurredAt.IsZero() {
		t.Fatalf("event = %+v, want an id and a date", event)
	}
	if event.CorrelationID != request.CorrelationID || event.UserID != request.UserID || event.Reason != "stale_heartbeat" || event.CurrentFeedSize != 4 {
		t.Fatalf("event = %+v, from %+v", event, request)
	}
	if len(event.ExcludedPostIDs) != 1 || event.ExcludedPostIDs[0] != request.ExcludedPostIDs[0] {
		t.Fatalf("excluded = %v", event.ExcludedPostIDs)
	}
}

func TestFeedPrefetchPublisher_PublishFeedPrefetch_ReturnsAFailure(t *testing.T) {
	wantErr := errors.New("outbox failed")

	err := feedrabbitmq.NewFeedPrefetchPublisher(&fakeEventPublisher{err: wantErr}).PublishFeedPrefetch(context.Background(), usecase.FeedPrefetchRequest{})

	if !errors.Is(err, wantErr) {
		t.Fatalf("PublishFeedPrefetch() error = %v, want it to wrap %v", err, wantErr)
	}
}
