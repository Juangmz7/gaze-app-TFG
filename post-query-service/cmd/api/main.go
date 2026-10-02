package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/Juangmz7/TFG/common-packages/go-utils/server"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
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

	rabbitmqResult, err := bootstrap.Bootstrap(ctx, cfg.RabbitMQ.AMQPURI(), mongoDatabase, slog.Default())
	if err != nil {
		return err
	}
	defer func() {
		if closeErr := rabbitmqResult.Close(); closeErr != nil {
			slog.Error("rabbitmq shutdown failed", "error", closeErr)
		}
	}()

	routerErrCh := make(chan error, 1)
	go func() {
		routerErrCh <- rabbitmqResult.Router.Run(ctx)
	}()

	slog.Info("consuming rabbitmq events")

	// mongoDatabase is also the injection point for future HTTP read
	// repositories (e.g. post/infrastructure, comment/infrastructure).
	if err := server.StartServer(ctx, serverCfg.Port, nil); err != nil {
		return err
	}

	if err := rabbitmqResult.Router.Close(); err != nil {
		slog.Error("rabbitmq router close failed", "error", err)
	}
	if err := <-routerErrCh; err != nil {
		slog.Error("rabbitmq router stopped with error", "error", err)
	}

	return nil
}
