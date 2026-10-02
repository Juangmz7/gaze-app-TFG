// Package bootstrap assembles the Watermill router, AMQP subscribers for
// every queue post-query-service consumes, the feed-events publisher, and
// every bounded-context handler, wiring them together the way cmd/api/main.go
// expects.
package bootstrap

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ThreeDotsLabs/watermill"
	wmamqp "github.com/ThreeDotsLabs/watermill-amqp/v3/pkg/amqp"
	"github.com/ThreeDotsLabs/watermill/message"
	"go.mongodb.org/mongo-driver/v2/mongo"

	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	collabrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	commentusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
	commentrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/rabbitmq"
	likeusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
	likemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/mongo"
	likerabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/rabbitmq"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	postrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/rabbitmq"
	shareusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
	sharemongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/mongo"
	sharerabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/dispatch"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
	userusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
	usermongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/mongo"
	userrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/rabbitmq"
)

// Result holds every component main() needs to run and later shut down the
// RabbitMQ side of the service.
type Result struct {
	// Router is the Watermill router. Call Run(ctx) to start consuming and
	// Close() on shutdown.
	Router *message.Router
	// FeedDeletedPublisher publishes UserFeedDeletedEvent to x.feed.events.
	FeedDeletedPublisher *publisher.Publisher
	// closers are closed, in order, when the caller is done with Result.
	closers []func() error
}

// Close closes every subscriber/publisher connection opened by Bootstrap, in
// addition to the Router itself.
func (r Result) Close() error {
	var firstErr error
	for _, closeFn := range r.closers {
		if err := closeFn(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

// Bootstrap builds the Watermill router with one consumer handler per queue
// (user fast, user slow, post), the idempotency repository, every
// bounded-context handler, and the feed-events publisher.
func Bootstrap(ctx context.Context, amqpURI string, db *mongo.Database, logger *slog.Logger) (Result, error) {
	wmLogger := watermill.NewSlogLogger(logger)

	wmRouter, err := message.NewRouter(message.RouterConfig{}, wmLogger)
	if err != nil {
		return Result{}, fmt.Errorf("bootstrap rabbitmq: create router: %w", err)
	}
	wmRouter.AddMiddleware(router.NewRetryMiddleware(wmLogger).Middleware)

	idempotencyRepo := idempotency.NewRepository(db)
	if err := idempotencyRepo.EnsureIndexes(ctx); err != nil {
		return Result{}, fmt.Errorf("bootstrap rabbitmq: %w", err)
	}

	result := Result{Router: wmRouter}

	if err := addUserFastConsumer(wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}
	if err := addUserSlowConsumer(wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}
	if err := addPostConsumer(wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}

	pub, err := newFeedDeletedPublisher(amqpURI, wmLogger, &result)
	if err != nil {
		return Result{}, err
	}
	result.FeedDeletedPublisher = pub

	return result, nil
}

func addUserFastConsumer(wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.UserFastSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	uc := userusecase.NewRegisterUser(usermongo.NewRepository(db))
	handler := userrabbitmq.NewUserRegisteredHandler(idempotencyRepo, uc, logger)

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.user.registered": handler.Handle,
	}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("user-fast-consumer", spec.Queue, subscriber, dispatcher.Handle)

	return nil
}

func addUserSlowConsumer(wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.UserSlowSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	uc := userusecase.NewDeleteUser(usermongo.NewRepository(db))
	handler := userrabbitmq.NewUserDeletedHandler(idempotencyRepo, uc, logger)

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.user.deleted": handler.Handle,
	}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("user-slow-consumer", spec.Queue, subscriber, dispatcher.Handle)

	return nil
}

func addPostConsumer(wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.PostSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	postCreatedHandler := postrabbitmq.New(idempotencyRepo, postusecase.New(postmongo.NewRepository(db)), logger)
	likeCreatedHandler := likerabbitmq.New(idempotencyRepo, likeusecase.New(likemongo.NewRepository(db)), logger)
	commentCreatedHandler := commentrabbitmq.New(idempotencyRepo, commentusecase.New(commentmongo.NewRepository(db)), logger)
	shareCreatedHandler := sharerabbitmq.New(idempotencyRepo, shareusecase.New(sharemongo.NewRepository(db)), logger)
	collabOpenedHandler := collabrabbitmq.New(idempotencyRepo, collabusecase.New(collabmongo.NewRepository(db)), logger)

	// Every routing key listed in topology.PostRoutingKeys is bound to this
	// queue at the AMQP level (see topology.Builder). Only the subset below
	// has a concrete use case so far; the remaining routing keys are valid,
	// bound deliveries that dispatch.Dispatcher logs and acknowledges until
	// their own use case is implemented (see feature_list.json follow-up
	// work, task 41 deviation noted in the PR description).
	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.post.created":                    postCreatedHandler.Handle,
		"rk.post.like.created":               likeCreatedHandler.Handle,
		"rk.post.comment.created":            commentCreatedHandler.Handle,
		"rk.post.share.created":              shareCreatedHandler.Handle,
		"rk.post.collab.opened.post-created": collabOpenedHandler.Handle,
	}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("post-consumer", spec.Queue, subscriber, dispatcher.Handle)

	return nil
}

func newSubscriber(amqpURI string, spec topology.Spec, wmLogger watermill.LoggerAdapter) (*wmamqp.Subscriber, error) {
	cfg := router.NewSubscriberConfig(amqpURI, spec)

	subscriber, err := wmamqp.NewSubscriber(cfg, wmLogger)
	if err != nil {
		return nil, fmt.Errorf("bootstrap rabbitmq: create subscriber for queue %q: %w", spec.Queue, err)
	}

	return subscriber, nil
}

func newFeedDeletedPublisher(amqpURI string, wmLogger watermill.LoggerAdapter, result *Result) (*publisher.Publisher, error) {
	cfg := router.NewPublisherConfig(amqpURI, topology.ExchangeFeedEvents)

	amqpPublisher, err := wmamqp.NewPublisher(cfg, wmLogger)
	if err != nil {
		return nil, fmt.Errorf("bootstrap rabbitmq: create feed events publisher: %w", err)
	}
	result.closers = append(result.closers, amqpPublisher.Close)

	return publisher.NewPublisher(amqpPublisher), nil
}
