// Package rabbitmq connects the feed's usecases to RabbitMQ: the outbox request to
// recommendation-service and the handler of its response.
package rabbitmq

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
)

// FeedPrefetchEventPublisher stores the event in the outbox (publisher.Publisher).
type FeedPrefetchEventPublisher interface {
	Publish(ctx context.Context, event publisher.Event) error
}

// FeedPrefetchPublisher turns a usecase.FeedPrefetchRequest into UserFeedExhaustedEvent.
type FeedPrefetchPublisher struct {
	events FeedPrefetchEventPublisher
}

// NewFeedPrefetchPublisher creates a FeedPrefetchPublisher.
func NewFeedPrefetchPublisher(events FeedPrefetchEventPublisher) *FeedPrefetchPublisher {
	return &FeedPrefetchPublisher{events: events}
}

// PublishFeedPrefetch implements usecase.RequestFeedPrefetchPublisher.
func (p *FeedPrefetchPublisher) PublishFeedPrefetch(ctx context.Context, request usecase.FeedPrefetchRequest) error {
	err := p.events.Publish(ctx, publisher.Event{
		ID:              uuid.New(),
		CorrelationID:   request.CorrelationID,
		OccurredAt:      time.Now().UTC(),
		UserID:          request.UserID,
		Reason:          string(request.Reason),
		CurrentFeedSize: request.CurrentFeedSize,
		ExcludedPostIDs: request.ExcludedPostIDs,
	})
	if err != nil {
		return fmt.Errorf("publish feed prefetch: %w", err)
	}

	return nil
}
