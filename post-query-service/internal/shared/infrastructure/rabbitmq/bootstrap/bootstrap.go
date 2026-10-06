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

	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	blockrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/rabbitmq"
	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	collabrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	commentusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
	commentrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/rabbitmq"
	followusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	followmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/mongo"
	followrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/rabbitmq"
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
	// FeedExhaustedPublisher publishes UserFeedExhaustedEvent to x.feed.events.
	FeedExhaustedPublisher *publisher.Publisher
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
// (user fast, user slow, post, feed), the idempotency repository, every
// bounded-context handler, and the feed-events publisher.
func Bootstrap(ctx context.Context, amqpURI string, db *mongo.Database, logger *slog.Logger) (Result, error) {
	wmLogger := watermill.NewSlogLogger(logger)

	wmRouter, err := message.NewRouter(message.RouterConfig{}, wmLogger)
	if err != nil {
		return Result{}, fmt.Errorf("bootstrap rabbitmq: create router: %w", err)
	}
	wmRouter.AddMiddleware(router.NewRetryMiddleware(wmLogger).Middleware)

	result := Result{Router: wmRouter}
	ok := false
	defer func() {
		if !ok {
			result.Close()
		}
	}()

	idempotencyRepo := idempotency.NewRepository(db)
	if err := idempotencyRepo.EnsureIndexes(ctx); err != nil {
		return Result{}, fmt.Errorf("bootstrap rabbitmq: %w", err)
	}

	if err := addUserFastConsumer(ctx, wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}
	if err := addUserSlowConsumer(wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}
	if err := addPostConsumer(ctx, wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}
	if err := addFeedConsumer(wmRouter, amqpURI, db, idempotencyRepo, logger, &result); err != nil {
		return Result{}, err
	}

	pub, err := newFeedExhaustedPublisher(amqpURI, wmLogger, &result)
	if err != nil {
		return Result{}, err
	}
	result.FeedExhaustedPublisher = pub

	ok = true
	return result, nil
}

func addUserFastConsumer(ctx context.Context, wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.UserFastSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	userRepository := usermongo.NewRepository(db)
	blockRepository := blockmongo.NewRepository(db)
	followRepository := followmongo.NewRepository(db)
	for _, ensure := range []func(context.Context) error{
		userRepository.EnsureIndexes,
		blockRepository.EnsureIndexes,
		followRepository.EnsureIndexes,
	} {
		if err := ensure(ctx); err != nil {
			return fmt.Errorf("bootstrap rabbitmq: %w", err)
		}
	}

	registerUserHandler := userrabbitmq.NewUserRegisteredHandler(idempotencyRepo, userusecase.NewRegisterUser(userRepository), logger)
	updateUserHandler := userrabbitmq.NewUserUpdatedHandler(idempotencyRepo, userusecase.NewUpdateUser(userRepository), logger)
	createBlockHandler := blockrabbitmq.NewUserBlockCreatedHandler(idempotencyRepo, blockusecase.NewCreateBlock(blockRepository), logger)
	createFollowHandler := followrabbitmq.NewUserFollowCreatedHandler(idempotencyRepo, followusecase.NewCreateFollow(followRepository), logger)

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.user.registered":     registerUserHandler.Handle,
		"rk.user.updated":        updateUserHandler.Handle,
		"rk.user.block.created":  createBlockHandler.Handle,
		"rk.user.follow.created": createFollowHandler.Handle,
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

	deleteUserHandler := userrabbitmq.NewUserDeletedHandler(idempotencyRepo, userusecase.NewDeleteUser(usermongo.NewRepository(db)), logger)
	deleteBlockHandler := blockrabbitmq.NewUserBlockDeletedHandler(idempotencyRepo, blockusecase.NewDeleteBlock(blockmongo.NewRepository(db)), logger)
	deleteFollowHandler := followrabbitmq.NewUserFollowDeletedHandler(idempotencyRepo, followusecase.NewDeleteFollow(followmongo.NewRepository(db)), logger)

	handlers := map[string]dispatch.EventHandlerFunc{
		"rk.user.deleted":        deleteUserHandler.Handle,
		"rk.user.block.deleted":  deleteBlockHandler.Handle,
		"rk.user.follow.deleted": deleteFollowHandler.Handle,
	}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("user-slow-consumer", spec.Queue, subscriber, dispatcher.Handle)

	return nil
}

func addPostConsumer(ctx context.Context, wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.PostSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	postRepository := postmongo.NewRepository(db)
	likeRepository := likemongo.NewRepository(db)
	commentRepository := commentmongo.NewRepository(db)
	shareRepository := sharemongo.NewRepository(db)
	collabRepository := collabmongo.NewRepository(db)
	for _, ensure := range []func(context.Context) error{
		postRepository.EnsureIndexes,
		likeRepository.EnsureIndexes,
		commentRepository.EnsureIndexes,
		shareRepository.EnsureIndexes,
		collabRepository.EnsureIndexes,
	} {
		if err := ensure(ctx); err != nil {
			return fmt.Errorf("bootstrap rabbitmq: %w", err)
		}
	}

	postCreatedHandler := postrabbitmq.NewPostCreatedHandler(idempotencyRepo, postusecase.New(postRepository), logger)
	postCollabLinkedHandler := postrabbitmq.NewPostCollabLinkedHandler(idempotencyRepo, postusecase.New(postRepository), logger)
	likeCreatedHandler := likerabbitmq.New(idempotencyRepo, likeusecase.New(likeRepository), logger)
	commentCreatedHandler := commentrabbitmq.New(idempotencyRepo, commentusecase.New(commentRepository), logger)
	shareCreatedHandler := sharerabbitmq.New(idempotencyRepo, shareusecase.New(shareRepository), logger)
	collabOpenedHandler := collabrabbitmq.NewPostCollabOpenedHandler(idempotencyRepo, collabusecase.NewRecordCollabOpened(collabRepository), logger)
	collabClosedHandler := collabrabbitmq.NewPostCollabClosedHandler(idempotencyRepo, collabusecase.NewCloseCollab(collabRepository), logger)

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
		"rk.post.collab.closed":              collabClosedHandler.Handle,
		"rk.post.collab.linked":              postCollabLinkedHandler.Handle,
	}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("post-consumer", spec.Queue, subscriber, dispatcher.Handle)

	return nil
}

func addFeedConsumer(wmRouter *message.Router, amqpURI string, db *mongo.Database, idempotencyRepo *idempotency.Repository, logger *slog.Logger, result *Result) error {
	spec := topology.FeedSpec()

	subscriber, err := newSubscriber(amqpURI, spec, watermill.NewSlogLogger(logger))
	if err != nil {
		return err
	}
	result.closers = append(result.closers, subscriber.Close)

	// Every routing key listed in topology.FeedRoutingKeys is bound to this
	// queue at the AMQP level (see topology.Builder). Neither
	// "rk.post.recommended.sent" nor "rk.post.trending.sent" has a concrete
	// use case yet; both are valid, bound deliveries that dispatch.Dispatcher
	// logs and acknowledges until their own use case is implemented.
	handlers := map[string]dispatch.EventHandlerFunc{}
	dispatcher := dispatch.New(spec.Queue, handlers, logger)

	wmRouter.AddConsumerHandler("feed-consumer", spec.Queue, subscriber, dispatcher.Handle)

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

func newFeedExhaustedPublisher(amqpURI string, wmLogger watermill.LoggerAdapter, result *Result) (*publisher.Publisher, error) {
	cfg := router.NewPublisherConfig(amqpURI, topology.ExchangeFeedEvents)

	amqpPublisher, err := wmamqp.NewPublisher(cfg, wmLogger)
	if err != nil {
		return nil, fmt.Errorf("bootstrap rabbitmq: create feed events publisher: %w", err)
	}
	result.closers = append(result.closers, amqpPublisher.Close)

	return publisher.NewPublisher(amqpPublisher), nil
}
