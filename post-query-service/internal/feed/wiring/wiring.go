// Package wiring assembles the feed context: it ensures the feed's indexes and builds
// its repositories, usecases, HTTP handlers, sweeper and recommendation-service client.
// cmd/api/main.go only starts, waits for and closes what it returns.
package wiring

import (
	"context"
	"fmt"
	"log/slog"

	"go.mongodb.org/mongo-driver/v2/mongo"

	feedusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedgrpc "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	feedrabbitmq "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/rabbitmq"
	feedsweeper "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/sweeper"
	feedpresentation "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
)

// percent turns a configured percentage into a fraction.
const percent = 100

// Params is what the feed is built from.
type Params struct {
	// Client runs the prefetch request's transaction.
	Client *mongo.Client
	// DB holds the feed's collections and the read models it hydrates from.
	DB *mongo.Database
	// Events is the one publisher, created from the outbox store the relay uses.
	Events *publisher.Publisher
	// Feed holds the feed's limits and timings.
	Feed config.FeedConfig
	// Recommendation holds the settings of the recommendation-service client.
	Recommendation config.RecommendationConfig
	// Logger is shared by every feed component.
	Logger *slog.Logger
}

// Feed is what main shares between the HTTP routes, the sweeper and the RabbitMQ
// bootstrap, which is rebuilt on every reconnect.
type Feed struct {
	// Routes are the feed's HTTP handlers, for feedpresentation.RegisterRoutes.
	Routes feedpresentation.Routes
	// Dependencies are the feed components every RabbitMQ bootstrap attempt shares.
	Dependencies bootstrap.Dependencies

	sweeper         *feedsweeper.PrefetchSweeper
	lowWatermark    *feedusecase.RequestLowWatermarkPrefetchUsecase
	recommendations *feedgrpc.RecommendationClient
}

// New ensures the feed's indexes and wires its usecases and handlers around one
// publisher. The caller must Close the returned Feed.
func New(ctx context.Context, params Params) (*Feed, error) {
	cfg := params.Feed
	logger := params.Logger

	sessions := feedmongo.NewSessionRepository(params.DB)
	if err := sessions.EnsureIndexes(ctx); err != nil {
		return nil, err
	}
	units := feedmongo.NewFeedUnitRepository(params.DB)
	if err := units.EnsureIndexes(ctx, cfg.Retention); err != nil {
		return nil, err
	}
	hydration := feedmongo.NewHydrationRepository(params.DB)

	recommendations, err := newRecommendationClient(params.Recommendation, logger)
	if err != nil {
		return nil, err
	}

	lease := feedusecase.NewFeedLeaseManager(sessions, feedusecase.FeedLeaseConfig{
		Owner:          cfg.InstanceID,
		TTL:            cfg.LeaseTTL,
		ReleaseTimeout: cfg.LeaseReleaseTimeout,
	}, logger)
	backlog := feedusecase.NewFeedBacklog(units, feedusecase.FeedBacklogConfig{
		MaxUnserved:    cfg.PrefetchMaxUnserved,
		ExcludedIDsCap: cfg.ExcludedIDsCap,
	}, logger)
	prefetch := feedusecase.NewRequestFeedPrefetch(database.NewTransactor(params.Client), backlog, lease, sessions,
		feedrabbitmq.NewFeedPrefetchPublisher(params.Events), cfg.PrefetchTimeout, logger)
	lowWatermark := feedusecase.NewRequestLowWatermarkPrefetch(backlog, prefetch, feedusecase.RequestLowWatermarkPrefetchConfig{
		PageSize:    cfg.DefaultPageSize,
		Concurrency: cfg.LowWatermarkConcurrency,
		Timeout:     cfg.PrefetchTimeout,
	}, logger)
	sweep := feedusecase.NewSweepStaleFeedSessions(sessions, prefetch, feedusecase.SweepStaleFeedSessionsConfig{
		StaleAfter: cfg.SweepStaleAfter,
		BatchSize:  cfg.SweepBatchSize,
	}, logger)

	refill := feedusecase.NewRefillFeed(units, lease, backlog, recommendations, feedusecase.NewBuildFeedUnits(hydration, sessions, units, logger), feedusecase.RefillFeedConfig{
		LeaseWait:    cfg.RefillLeaseWait,
		PollInterval: cfg.RefillPollInterval,
		RetryAfter:   cfg.RefillRetryAfter,
	}, logger)
	claim := feedusecase.NewClaimFeed(units, hydration, feedusecase.ClaimFeedConfig{
		DefaultPageSize: cfg.DefaultPageSize,
		MaxPageSize:     cfg.MaxPageSize,
		MaxReads:        cfg.MaxReadsPerClaim,
	}, logger).WithLowWatermark(lowWatermark).WithRefill(refill)

	return &Feed{
		Routes: feedpresentation.Routes{
			Heartbeat:  feedpresentation.NewHeartbeatHandler(feedusecase.NewRegisterHeartbeat(sessions, logger), logger),
			Disconnect: feedpresentation.NewDisconnectHandler(feedusecase.NewRegisterUserDisconnected(sessions, prefetch, cfg.DisconnectWriteTimeout, logger), logger),
			ClaimFeed:  feedpresentation.NewClaimFeedHandler(claim, logger),
		},
		Dependencies:    bootstrap.Dependencies{Prefetch: prefetch, Lease: lease},
		sweeper:         feedsweeper.NewPrefetchSweeper(sweep, cfg.SweepInterval, logger),
		lowWatermark:    lowWatermark,
		recommendations: recommendations,
	}, nil
}

// StartSweeper starts the stale-session sweeper. The returned channel closes once it
// has stopped after ctx is cancelled.
func (f *Feed) StartSweeper(ctx context.Context) <-chan struct{} {
	return f.sweeper.Start(ctx)
}

// WaitLowWatermark stops new low-watermark checks and waits for those in flight.
func (f *Feed) WaitLowWatermark() {
	f.lowWatermark.Wait()
}

// Close closes the recommendation-service client.
func (f *Feed) Close() error {
	if err := f.recommendations.Close(); err != nil {
		return fmt.Errorf("close recommendation client: %w", err)
	}

	return nil
}

func newRecommendationClient(cfg config.RecommendationConfig, logger *slog.Logger) (*feedgrpc.RecommendationClient, error) {
	return feedgrpc.NewRecommendationClient(feedgrpc.RecommendationClientConfig{
		Address:         cfg.Address,
		DialTimeout:     cfg.DialTimeout,
		PerRetryTimeout: cfg.PerRetryTimeout,
		OverallTimeout:  cfg.OverallTimeout,
		MaxAttempts:     uint(cfg.MaxAttempts),
		BackoffBase:     cfg.BackoffBase,
		BackoffMax:      cfg.BackoffMax,
		BackoffJitter:   float64(cfg.BackoffJitterPercent) / percent,
	}, logger)
}
