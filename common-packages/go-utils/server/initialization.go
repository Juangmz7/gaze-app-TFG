package server

import (
	"context"
	"fmt"
	"log"
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

	go func() {
		log.Printf("HTTP server listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errChan <- err
		}
	}()

	select {
	case <-ctx.Done():
		log.Println("shutting down gracefully...")
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer shutdownCancel()

		if err := srv.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("HTTP server shutdown failed: %w", err)
		}
		log.Println("server stopped.")
		return nil
	case err := <-errChan:
		return fmt.Errorf("server error: %w", err)
	}
}
