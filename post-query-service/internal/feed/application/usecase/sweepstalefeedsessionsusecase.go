package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// FeedStaleSession is one user the sweeper found due a prefetch.
type FeedStaleSession struct {
	UserID            uuid.UUID
	LastHeartbeatDate time.Time
}

// SweepStaleFeedSessionsConfig holds the sweep's settings (config.FeedConfig).
type SweepStaleFeedSessionsConfig struct {
	// StaleAfter is how old a last heartbeat must be.
	StaleAfter time.Duration
	// BatchSize is how many sessions one read returns.
	BatchSize int
}

// SweepStaleFeedSessionsOutput counts what one sweep did.
type SweepStaleFeedSessionsOutput struct {
	Selected  int
	Published int
	Failed    int
}

// SweepStaleFeedSessionsRepository finds stale sessions, oldest heartbeat first, after
// the cursor. Implemented by feed/infrastructure/mongo.SessionRepository.
type SweepStaleFeedSessionsRepository interface {
	FindStaleSessions(ctx context.Context, staleBefore time.Time, after *time.Time, limit int) ([]FeedStaleSession, error)
}

// SweepStaleFeedSessionsRequester is RequestFeedPrefetchUsecase.
type SweepStaleFeedSessionsRequester interface {
	Execute(ctx context.Context, input RequestFeedPrefetchInput) (RequestFeedPrefetchOutput, error)
}

// SweepStaleFeedSessionsUsecase requests a prefetch for every user who went quiet
// without a disconnect. Every replica runs it; the lease is what prevents duplicates.
type SweepStaleFeedSessionsUsecase struct {
	repository SweepStaleFeedSessionsRepository
	prefetch   SweepStaleFeedSessionsRequester
	config     SweepStaleFeedSessionsConfig
	logger     *slog.Logger
}

// NewSweepStaleFeedSessions creates a SweepStaleFeedSessionsUsecase.
func NewSweepStaleFeedSessions(repository SweepStaleFeedSessionsRepository, prefetch SweepStaleFeedSessionsRequester, config SweepStaleFeedSessionsConfig, logger *slog.Logger) *SweepStaleFeedSessionsUsecase {
	return &SweepStaleFeedSessionsUsecase{repository: repository, prefetch: prefetch, config: config, logger: logger}
}

// Execute walks the stale sessions in bounded batches. A user whose prefetch fails is
// logged and stays due, for the next sweep; it does not stop the others. The cursor is
// what ends the walk: a user whose lease is held stays due and would otherwise be read
// again forever.
func (u *SweepStaleFeedSessionsUsecase) Execute(ctx context.Context) (SweepStaleFeedSessionsOutput, error) {
	if u.config.BatchSize <= 0 {
		return SweepStaleFeedSessionsOutput{}, fmt.Errorf("sweep stale feed sessions usecase: batch size %d is not positive", u.config.BatchSize)
	}

	correlationID := uuid.NewString()
	staleBefore := time.Now().UTC().Add(-u.config.StaleAfter)

	var output SweepStaleFeedSessionsOutput
	var cursor *time.Time
	for {
		sessions, err := u.repository.FindStaleSessions(ctx, staleBefore, cursor, u.config.BatchSize)
		if err != nil {
			return output, fmt.Errorf("sweep stale feed sessions usecase: %w", err)
		}

		for _, session := range sessions {
			if err := ctx.Err(); err != nil {
				return output, fmt.Errorf("sweep stale feed sessions usecase: %w", err)
			}
			u.request(ctx, session, correlationID, &output)
		}

		if len(sessions) < u.config.BatchSize {
			break
		}
		last := sessions[len(sessions)-1].LastHeartbeatDate
		cursor = &last
	}

	if output.Selected > 0 {
		u.logger.InfoContext(ctx, "feed sweep finished", "correlation_id", correlationID, "selected", output.Selected, "published", output.Published, "failed", output.Failed)
	}

	return output, nil
}

func (u *SweepStaleFeedSessionsUsecase) request(ctx context.Context, session FeedStaleSession, correlationID string, output *SweepStaleFeedSessionsOutput) {
	output.Selected++

	result, err := u.prefetch.Execute(ctx, RequestFeedPrefetchInput{UserID: session.UserID, Reason: FeedPrefetchReasonStaleHeartbeat, CorrelationID: correlationID})
	if err != nil {
		output.Failed++
		u.logger.ErrorContext(ctx, "feed sweep prefetch failed", "correlation_id", correlationID, "userId", session.UserID, "error", err)
		return
	}
	if result.Status == RequestFeedPrefetchPublished {
		output.Published++
	}
}
