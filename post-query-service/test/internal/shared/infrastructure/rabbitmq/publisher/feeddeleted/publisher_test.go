package feeddeleted_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher/feeddeleted"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

type fakePublisher struct {
	gotTopic string
	gotMsg   *message.Message
	err      error
}

func (f *fakePublisher) Publish(topic string, messages ...*message.Message) error {
	f.gotTopic = topic
	if len(messages) > 0 {
		f.gotMsg = messages[0]
	}
	return f.err
}

func (f *fakePublisher) Close() error { return nil }

func TestPublisher_Publish_SendsTheEventOnTheFeedDeletedRoutingKey(t *testing.T) {
	fake := &fakePublisher{}
	publisher := feeddeleted.NewPublisher(fake)

	event := feeddeleted.Event{
		ID:            uuid.New(),
		CorrelationID: uuid.New(),
		OccurredAt:    time.Now().UTC(),
		UserID:        uuid.New(),
	}

	if err := publisher.Publish(context.Background(), event); err != nil {
		t.Fatalf("Publish() error = %v, want nil", err)
	}

	if fake.gotTopic != topology.RKFeedDeleted {
		t.Fatalf("Publish() topic = %q, want %q", fake.gotTopic, topology.RKFeedDeleted)
	}

	var gotEvent feeddeleted.Event
	if err := json.Unmarshal(fake.gotMsg.Payload, &gotEvent); err != nil {
		t.Fatalf("json.Unmarshal() error = %v", err)
	}
	if gotEvent.UserID != event.UserID {
		t.Fatalf("published payload UserID = %v, want %v", gotEvent.UserID, event.UserID)
	}
}

func TestPublisher_Publish_ReturnsErrorWhenEventIDIsMissing(t *testing.T) {
	publisher := feeddeleted.NewPublisher(&fakePublisher{})

	event := feeddeleted.Event{UserID: uuid.New()}

	if err := publisher.Publish(context.Background(), event); err == nil {
		t.Fatal("Publish() error = nil, want an error when id is missing")
	}
}

func TestPublisher_Publish_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	publisher := feeddeleted.NewPublisher(&fakePublisher{})

	event := feeddeleted.Event{ID: uuid.New()}

	if err := publisher.Publish(context.Background(), event); err == nil {
		t.Fatal("Publish() error = nil, want an error when user_id is missing")
	}
}

func TestPublisher_Publish_PropagatesThePublisherError(t *testing.T) {
	wantErr := errors.New("broker unavailable")
	publisher := feeddeleted.NewPublisher(&fakePublisher{err: wantErr})

	event := feeddeleted.Event{ID: uuid.New(), UserID: uuid.New()}

	if err := publisher.Publish(context.Background(), event); !errors.Is(err, wantErr) {
		t.Fatalf("Publish() error = %v, want it to wrap %v", err, wantErr)
	}
}
