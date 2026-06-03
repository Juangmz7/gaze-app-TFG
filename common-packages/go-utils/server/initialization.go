package server

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"time"
)

// StartServer starts the HTTP server and handles graceful shutdown.
func StartServer(ctx context.Context, port string, handler http.Handler) error {
	srv := &http.Server{
		Addr:    fmt.Sprintf(":%s", port),
		Handler: handler,
	}

	errChan := make(chan error, 1)

	go startListener(srv, errChan)

	return handleShutdown(ctx, srv, errChan)
}

func startListener(srv *http.Server, errChan chan<- error) {
		slog.Info("HTTP server listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errChan <- err
		}
}

func handleShutdown(ctx context.Context, srv *http.Server, errChan <-chan error) error {
	select {
	case <-ctx.Done():
		slog.Info("shutting down gracefully...")
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer shutdownCancel()

		if err := srv.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("HTTP server shutdown failed: %w", err)
		}
		slog.Info("server stopped.")
		return nil
	case err := <-errChan:
		return fmt.Errorf("server error: %w", err)
	}
}
