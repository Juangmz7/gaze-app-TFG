package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/Juangmz7/TFG/common-packages/go-utils/server"
	"github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"
	feedpresentation "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/presentation"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/wiring"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/health"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/outbox"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/publisher"
)

// healthzPathPrefix covers the liveness and readiness probes, which are
// excluded from HTTP tracing so kubelet polling does not flood Tempo.
const healthzPathPrefix = "/healthz/"

// defaultServiceName is used when OTEL_SERVICE_NAME is not set.
const defaultServiceName = "post-query-service"

// telemetryShutdownTimeout bounds the final flush of spans, metrics and logs.
const telemetryShutdownTimeout = 5 * time.Second

func main() {
	err := run()
	if err != nil {
		slog.Error("application failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	startedAt := time.Now()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Traces, metrics and logs over OTLP, configured from the OTEL_* env vars
	// (endpoint, OTEL_RESOURCE_ATTRIBUTES, OTEL_TRACES_SAMPLER_ARG). It also
	// installs the default slog logger (JSON stdout + OTLP, with trace_id).
	telemetryCfg := telemetry.Config{}
	serviceName := strings.TrimSpace(os.Getenv(telemetry.EnvServiceName))
	if serviceName == "" {
		serviceName = defaultServiceName
		telemetryCfg.ServiceName = defaultServiceName
	}
	shutdownTelemetry, err := telemetry.Init(ctx, telemetryCfg)
	if err != nil {
		return err
	}
	// Registered before any other defer so it runs last: after <-rabbitmqDone
	// and the Mongo disconnect, so telemetry from the whole shutdown is flushed.
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), telemetryShutdownTimeout)
		defer cancel()
		if shutdownErr := shutdownTelemetry(shutdownCtx); shutdownErr != nil {
			slog.Error("telemetry shutdown failed", "error", shutdownErr)
		}
	}()

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

	feed, err := wiring.New(ctx, wiring.Params{
		Client:         mongoClient,
		DB:             mongoDatabase,
		Events:         publisher.NewPublisher(outboxStore),
		Feed:           cfg.Feed,
		Recommendation: cfg.Recommendation,
		Logger:         slog.Default(),
	})
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := feed.Close(); closeErr != nil {
			slog.Error("feed close failed", "error", closeErr)
		}
	}()
	sweeperDone := feed.StartSweeper(ctx)

	// RunWithRetry rebuilds the router and subscribers from scratch on
	// every AMQP connection loss, with linear backoff, instead of letting a
	// single bootstrap.Bootstrap failure silently stop event consumption for
	// good while the HTTP server keeps reporting healthy.
	rabbitmqDone := bootstrap.RunWithRetry(ctx, cfg.RabbitMQ.AMQPURI(), mongoDatabase, feed.Dependencies, slog.Default())

	slog.Info("consuming rabbitmq events")

	mux := http.NewServeMux()
	health.Register(mux, mongoClient)
	feedpresentation.RegisterRoutes(mux, auth.Authenticate(keyCache, cfg.Auth.Issuer, cfg.Auth.Audience), feed.Routes)

	handler := otelhttp.NewHandler(mux, "http.server",
		otelhttp.WithFilter(func(r *http.Request) bool {
			return !strings.HasPrefix(r.URL.Path, healthzPathPrefix)
		}),
		// Low-cardinality span names ("GET"); the route goes in attributes.
		otelhttp.WithSpanNameFormatter(func(_ string, r *http.Request) string {
			return r.Method
		}),
	)

	// Startup setup time, like Spring Boot's "Started ... in N seconds": from
	// the start of run() to every dependency wired, right before serving.
	startupSeconds := time.Since(startedAt).Seconds()
	slog.Info(fmt.Sprintf("started %s in %.3f seconds", serviceName, startupSeconds),
		"startup_seconds", startupSeconds)

	serverErr := server.StartServer(ctx, serverCfg.Port, handler)

	// A shutdown past its budget and a failed listener both return an error, and the waits
	// below still have to run. Only a signal has cancelled ctx so far: without this, a
	// failed listener would leave the background work running and the waits hanging.
	stop()

	// Everything that stores outbox events stops first, and the relay's immediate sends
	// are waited for last, before the deferred publisher close, Mongo disconnect and
	// telemetry shutdown.
	<-sweeperDone
	feed.WaitLowWatermark()

	// Block until the retry loop has torn down its current RabbitMQ
	// resources and exited, so run() does not disconnect Mongo (via its own
	// deferred call above) while RabbitMQ cleanup is still in flight.
	<-rabbitmqDone
	<-outboxDone
	outboxRelay.Wait()

	return serverErr
}
