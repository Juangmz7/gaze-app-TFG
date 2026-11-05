package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// FeedBacklogRepository reads a user's stored units. Implemented by
// feed/infrastructure/mongo.FeedUnitRepository.
type FeedBacklogRepository interface {
	CountUnserved(ctx context.Context, userID string, limit int64) (int64, error)
	FindAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool, limit int) ([]FeedUnitRef, error)
	FindRecentlyServedPostIDs(ctx context.Context, userID string, limit int) ([]string, error)
}

// FeedBacklogConfig holds the backlog's limits (config.FeedConfig).
type FeedBacklogConfig struct {
	// MaxUnserved is the backlog at which a prefetch is skipped.
	MaxUnserved int
	// ExcludedIDsCap caps the ids sent to recommendation-service as already known.
	ExcludedIDsCap int
}

// FeedBacklog answers what building more of a user's feed depends on: how much is
// still waiting, and which posts recommendation-service must not return.
type FeedBacklog struct {
	repository FeedBacklogRepository
	config     FeedBacklogConfig
	logger     *slog.Logger
}

// NewFeedBacklog creates a FeedBacklog.
func NewFeedBacklog(repository FeedBacklogRepository, config FeedBacklogConfig, logger *slog.Logger) *FeedBacklog {
	return &FeedBacklog{repository: repository, config: config, logger: logger}
}

// Unserved counts the user's unserved units, up to MaxUnserved, and reports whether
// that maximum is reached, so no feed grows without limit.
func (b *FeedBacklog) Unserved(ctx context.Context, userID uuid.UUID) (int64, bool, error) {
	limit := int64(b.config.MaxUnserved)
	count, err := b.repository.CountUnserved(ctx, userID.String(), limit)
	if err != nil {
		return 0, false, fmt.Errorf("feed backlog: %w", err)
	}

	return count, count >= limit, nil
}

// ExcludedPostIDs returns the user's unserved ids and then their most recently served
// ids, ExcludedIDsCap at most. Without this the pipeline returns posts already waiting
// in the feed, which the unique index discards, leaving nothing new. It never returns
// nil, so the field always reaches the wire as a list.
func (b *FeedBacklog) ExcludedPostIDs(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, error) {
	viewerID := userID.String()
	refs, err := b.repository.FindAfterPosition(ctx, viewerID, nil, true, b.config.ExcludedIDsCap)
	if err != nil {
		return nil, fmt.Errorf("feed backlog: %w", err)
	}

	stored := make([]string, 0, len(refs))
	for _, ref := range refs {
		stored = append(stored, ref.PostID)
	}
	if remaining := b.config.ExcludedIDsCap - len(stored); remaining > 0 {
		served, err := b.repository.FindRecentlyServedPostIDs(ctx, viewerID, remaining)
		if err != nil {
			return nil, fmt.Errorf("feed backlog: %w", err)
		}
		stored = append(stored, served...)
	}

	excluded := make([]uuid.UUID, 0, len(stored))
	for _, postID := range stored {
		parsed, err := uuid.Parse(postID)
		if err != nil {
			b.logger.WarnContext(ctx, "discarding a stored post id that is not a UUID", "userId", userID, "postId", postID)
			continue
		}
		excluded = append(excluded, parsed)
	}

	return excluded, nil
}
