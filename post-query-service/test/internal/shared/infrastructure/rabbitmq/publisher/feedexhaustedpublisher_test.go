package publisher_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

type fakeOutbox struct {
	got []outbox.Event
	err error
}

func (f *fakeOutbox) Add(_ context.Context, event outbox.Event) error {
	f.got = append(f.got, event)
	return f.err
}

func TestPublisher_Publish_StoresAPendingOutboxEventForTheFeedExhaustedRoutingKey(t *testing.T) {
	fake := &fakeOutbox{}
	pub := publisher.NewPublisher(fake)

	event := publisher.Event{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
	}

	if err := pub.Publish(context.Background(), event); err != nil {
		t.Fatalf("Publish() error = %v, want nil", err)
	}

	if len(fake.got) != 1 {
		t.Fatalf("outbox received %d events, want 1", len(fake.got))
	}
	stored := fake.got[0]
	if stored.ID != event.ID || stored.CorrelationID != event.CorrelationID {
		t.Fatalf("outbox ids = %v/%v, want the event's %v/%v", stored.ID, stored.CorrelationID, event.ID, event.CorrelationID)
	}
	if stored.Exchange != topology.ExchangeFeedEvents || stored.RoutingKey != topology.RKFeedExhausted {
		t.Fatalf("outbox destination = %s/%s, want %s/%s",
			stored.Exchange, stored.RoutingKey, topology.ExchangeFeedEvents, topology.RKFeedExhausted)
	}
	if stored.Status != outbox.StatusPending || stored.EventType != publisher.EventType {
		t.Fatalf("outbox status/type = %s/%s, want PENDING/%s", stored.Status, stored.EventType, publisher.EventType)
	}

	var payload map[string]any
	if err := json.Unmarshal([]byte(stored.Payload), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	for _, key := range []string{"id", "correlationId", "occurredAt", "userId"} {
		if _, ok := payload[key]; !ok {
			t.Fatalf("payload = %v, want camelCase key %q", payload, key)
		}
	}
}

func TestPublisher_Publish_ReturnsTheOutboxError(t *testing.T) {
	pub := publisher.NewPublisher(&fakeOutbox{err: errors.New("mongo unavailable")})

	if err := pub.Publish(context.Background(), publisher.Event{ID: uuid.New(), UserID: uuid.New()}); err == nil {
		t.Fatal("Publish() error = nil, want the outbox error")
	}
}

func TestPublisher_Publish_ReturnsErrorWhenEventIDIsMissing(t *testing.T) {
	pub := publisher.NewPublisher(&fakeOutbox{})

	event := publisher.Event{UserID: uuid.New()}

	if err := pub.Publish(context.Background(), event); err == nil {
		t.Fatal("Publish() error = nil, want an error when id is missing")
	}
}

func TestPublisher_Publish_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	pub := publisher.NewPublisher(&fakeOutbox{})

	event := publisher.Event{ID: uuid.New()}

	if err := pub.Publish(context.Background(), event); err == nil {
		t.Fatal("Publish() error = nil, want an error when userId is missing")
	}
}
