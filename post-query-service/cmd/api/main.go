package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/Juangmz7/TFG/common-packages/go-utils/server"
	feedusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	feedrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/rabbitmq"
	feedsweeper "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/sweeper"
	feedpresentation "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/health"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
	"go.mongodb.org/mongo-driver/v2/mongo"
)

func main() {
	err := run()
	if err != nil {
		slog.Error("application failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serverCfg := server.LoadServer()

	cfg, err := config.Load()
	if err != nil {
		return err
	}

	mongoClient, mongoDatabase, err := database.Connect(ctx, cfg.Mongo.URI, cfg.Mongo.Database)
	if err != nil {
		return err
	}
	defer func() {
		if disconnectErr := database.Disconnect(mongoClient); disconnectErr != nil {
			slog.Error("mongo disconnect failed", "error", disconnectErr)
		}
	}()

	slog.Info("connected to mongodb", "database", cfg.Mongo.Database)

	// Fetches the signing keys now, so a wrong JWKS_URL fails the start, not the first
	// request.
	keyCache, err := auth.NewCacheKey(ctx, cfg.Auth.JWKSUrl)
	if err != nil {
		return err
	}

	// Transactional outbox relay: publishes events stored by use cases (in the
	// same Mongo transaction as their state change) with publisher confirms,
	// in the background right after their commit, with the periodic batch as the
	// safety net.
	outboxStore := outbox.NewStore(mongoDatabase)
	if err := outboxStore.EnsureIndexes(ctx); err != nil {
		return err
	}
	outboxPublisher := outbox.NewAMQPPublisher(cfg.RabbitMQ.AMQPURI(), slog.Default())
	defer func() {
		if closeErr := outboxPublisher.Close(); closeErr != nil {
			slog.Error("outbox publisher close failed", "error", closeErr)
		}
	}()
	outboxRelay := outbox.NewRelay(outboxStore, outboxPublisher, outbox.DefaultRelayConfig(), slog.Default())
	outboxStore.SendImmediatelyWith(outboxRelay)
	outboxDone := outboxRelay.Start(ctx)

	feed, err := newFeedWiring(ctx, mongoClient, mongoDatabase, publisher.NewPublisher(outboxStore), cfg.Feed)
	if err != nil {
		return err
	}
	sweeperDone := feed.sweeper.Start(ctx)

	// RunWithRetry rebuilds the router and subscribers from scratch on
	// every AMQP connection loss, with linear backoff, instead of letting a
	// single bootstrap.Bootstrap failure silently stop event consumption for
	// good while the HTTP server keeps reporting healthy.
	rabbitmqDone := bootstrap.RunWithRetry(ctx, cfg.RabbitMQ.AMQPURI(), mongoDatabase, feed.dependencies, slog.Default())

	slog.Info("consuming rabbitmq events")

	mux := http.NewServeMux()
	health.Register(mux, mongoClient)
	feedpresentation.RegisterRoutes(mux, auth.Authenticate(keyCache, cfg.Auth.Issuer, cfg.Auth.Audience), feed.routes)

	if err := server.StartServer(ctx, serverCfg.Port, mux); err != nil {
		return err
	}

	// Everything that stores outbox events stops first, and the relay's immediate sends
	// are waited for last, before the deferred publisher close and Mongo disconnect.
	<-sweeperDone
	feed.lowWatermark.Wait()

	// Block until the retry loop has torn down its current RabbitMQ
	// resources and exited, so run() does not disconnect Mongo (via its own
	// deferred call above) while RabbitMQ cleanup is still in flight.
	<-rabbitmqDone
	<-outboxDone
	outboxRelay.Wait()

	return nil
}

// feedWiring is what main shares between the HTTP routes, the sweeper and the RabbitMQ
// bootstrap, which is rebuilt on every reconnect.
type feedWiring struct {
	routes       feedpresentation.Routes
	sweeper      *feedsweeper.PrefetchSweeper
	lowWatermark *feedusecase.RequestLowWatermarkPrefetchUsecase
	dependencies bootstrap.Dependencies
}

// newFeedWiring ensures the feed's indexes and wires its usecases and handlers around
// one publisher.
func newFeedWiring(ctx context.Context, client *mongo.Client, db *mongo.Database, events *publisher.Publisher, cfg config.FeedConfig) (feedWiring, error) {
	sessions := feedmongo.NewSessionRepository(db)
	if err := sessions.EnsureIndexes(ctx); err != nil {
		return feedWiring{}, err
	}
	units := feedmongo.NewFeedUnitRepository(db)
	if err := units.EnsureIndexes(ctx, cfg.Retention); err != nil {
		return feedWiring{}, err
	}
	hydration := feedmongo.NewHydrationRepository(db)

	logger := slog.Default()
	lease := feedusecase.NewFeedLeaseManager(sessions, feedusecase.FeedLeaseConfig{
		Owner:          cfg.InstanceID,
		TTL:            cfg.LeaseTTL,
		ReleaseTimeout: cfg.LeaseReleaseTimeout,
	}, logger)
	backlog := feedusecase.NewFeedBacklog(units, feedusecase.FeedBacklogConfig{
		MaxUnserved:    cfg.PrefetchMaxUnserved,
		ExcludedIDsCap: cfg.ExcludedIDsCap,
	}, logger)
	prefetch := feedusecase.NewRequestFeedPrefetch(database.NewTransactor(client), backlog, lease, sessions,
		feedrabbitmq.NewFeedPrefetchPublisher(events), cfg.PrefetchTimeout, logger)
	lowWatermark := feedusecase.NewRequestLowWatermarkPrefetch(backlog, prefetch, feedusecase.RequestLowWatermarkPrefetchConfig{
		PageSize:    cfg.DefaultPageSize,
		Concurrency: cfg.LowWatermarkConcurrency,
		Timeout:     cfg.PrefetchTimeout,
	}, logger)
	sweep := feedusecase.NewSweepStaleFeedSessions(sessions, prefetch, feedusecase.SweepStaleFeedSessionsConfig{
		StaleAfter: cfg.SweepStaleAfter,
		BatchSize:  cfg.SweepBatchSize,
	}, logger)

	claimConfig := feedusecase.ClaimFeedConfig{
		DefaultPageSize: cfg.DefaultPageSize,
		MaxPageSize:     cfg.MaxPageSize,
		MaxReads:        cfg.MaxReadsPerClaim,
	}
	claim := feedusecase.NewClaimFeed(units, hydration, claimConfig, logger).WithLowWatermark(lowWatermark)

	return feedWiring{
		routes: feedpresentation.Routes{
			Heartbeat:  feedpresentation.NewHeartbeatHandler(feedusecase.NewRegisterHeartbeat(sessions, logger), logger),
			Disconnect: feedpresentation.NewDisconnectHandler(feedusecase.NewRegisterUserDisconnected(sessions, prefetch, cfg.DisconnectWriteTimeout, logger), logger),
			ClaimFeed:  feedpresentation.NewClaimFeedHandler(claim, logger),
		},
		sweeper:      feedsweeper.NewPrefetchSweeper(sweep, cfg.SweepInterval, logger),
		lowWatermark: lowWatermark,
		dependencies: bootstrap.Dependencies{Prefetch: prefetch, Lease: lease},
	}, nil
}
