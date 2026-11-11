// Package sweeper runs the feed's scheduled work.
package sweeper

import (
	"context"
	"log/slog"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// PrefetchSweepUsecase runs one sweep (usecase.SweepStaleFeedSessionsUsecase).
type PrefetchSweepUsecase interface {
	Execute(ctx context.Context) (usecase.SweepStaleFeedSessionsOutput, error)
}

// PrefetchSweeper runs the sweep periodically.
type PrefetchSweeper struct {
	usecase  PrefetchSweepUsecase
	interval time.Duration
	logger   *slog.Logger
}

// NewPrefetchSweeper creates a PrefetchSweeper.
func NewPrefetchSweeper(uc PrefetchSweepUsecase, interval time.Duration, logger *slog.Logger) *PrefetchSweeper {
	return &PrefetchSweeper{usecase: uc, interval: interval, logger: logger}
}

// Start runs the sweep in its own goroutine, once per interval, until ctx is
// cancelled. The returned channel is closed once the loop has exited.
func (s *PrefetchSweeper) Start(ctx context.Context) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)

		ticker := time.NewTicker(s.interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}

			if _, err := s.usecase.Execute(ctx); err != nil && ctx.Err() == nil {
				s.logger.ErrorContext(ctx, "feed sweep failed", "error", err)
			}
		}
	}()

	return done
}
