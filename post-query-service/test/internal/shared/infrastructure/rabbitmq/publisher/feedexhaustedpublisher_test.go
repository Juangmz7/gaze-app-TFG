package publisher_test

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
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
		Reason:        "disconnect",
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

	if err := pub.Publish(context.Background(), publisher.Event{ID: uuid.New(), CorrelationID: uuid.New(), UserID: uuid.New(), Reason: "disconnect"}); err == nil {
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

// The field names and the empty list are the contract recommendation-service validates.
func TestPublisher_Publish_WritesTheCamelCaseContractOfRecommendationService(t *testing.T) {
	fake := &fakeOutbox{}
	excluded := uuid.New()
	event := publisher.Event{
		ID:              uuid.New(),
		CorrelationID:   uuid.New(),
		OccurredAt:      time.Now().UTC(),
		UserID:          uuid.New(),
		Reason:          "low_watermark",
		CurrentFeedSize: 7,
		ExcludedPostIDs: []uuid.UUID{excluded},
	}

	if err := publisher.NewPublisher(fake).Publish(context.Background(), event); err != nil {
		t.Fatalf("Publish() error = %v", err)
	}

	var payload map[string]any
	if err := json.Unmarshal([]byte(fake.got[0].Payload), &payload); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if payload["id"] != event.ID.String() || payload["correlationId"] != event.CorrelationID.String() || payload["userId"] != event.UserID.String() {
		t.Fatalf("payload ids = %v", payload)
	}
	if payload["reason"] != "low_watermark" || payload["currentFeedSize"] != float64(7) {
		t.Fatalf("payload reason/size = %v", payload)
	}
	ids, ok := payload["excludedPostIds"].([]any)
	if !ok || len(ids) != 1 || ids[0] != excluded.String() {
		t.Fatalf("excludedPostIds = %v, want [%v]", payload["excludedPostIds"], excluded)
	}
	if _, found := payload["occurredAt"]; !found {
		t.Fatalf("payload = %v, misses occurredAt", payload)
	}
}

func TestPublisher_Publish_WritesNoExclusionsAsAnEmptyList(t *testing.T) {
	fake := &fakeOutbox{}
	event := publisher.Event{ID: uuid.New(), CorrelationID: uuid.New(), UserID: uuid.New(), Reason: "user_registered"}

	if err := publisher.NewPublisher(fake).Publish(context.Background(), event); err != nil {
		t.Fatalf("Publish() error = %v", err)
	}

	if !strings.Contains(fake.got[0].Payload, `"excludedPostIds":[]`) {
		t.Fatalf("payload = %s, want an empty excludedPostIds list", fake.got[0].Payload)
	}
}

func TestPublisher_Publish_RejectsAnEventThatBreaksTheContract(t *testing.T) {
	valid := func() publisher.Event {
		return publisher.Event{ID: uuid.New(), CorrelationID: uuid.New(), UserID: uuid.New(), Reason: "disconnect"}
	}
	tests := []struct {
		name   string
		mutate func(*publisher.Event)
	}{
		{"no correlation id", func(event *publisher.Event) { event.CorrelationID = uuid.Nil }},
		{"no reason", func(event *publisher.Event) { event.Reason = "" }},
		{"negative feed size", func(event *publisher.Event) { event.CurrentFeedSize = -1 }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fake := &fakeOutbox{}
			event := valid()
			tt.mutate(&event)

			if err := publisher.NewPublisher(fake).Publish(context.Background(), event); err == nil {
				t.Fatal("Publish() error = nil, want an error")
			}
			if len(fake.got) != 0 {
				t.Fatalf("outbox received %d events, want none", len(fake.got))
			}
		})
	}
}
