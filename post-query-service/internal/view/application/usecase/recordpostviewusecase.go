// Package usecase implements the use case that projects PostViewedEvent into the
// viewing history read model (one row per (user, post), holding the latest view) and
// into the viewed post's view counter.
package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// RecordPostViewInput is one view of a post by a user, at ViewedAt (the event's
// serverTimestamp, set by post-command-service when it accepted the view).
// CorrelationID is only logged.
type RecordPostViewInput struct {
	UserID        uuid.UUID
	PostID        uuid.UUID
	ViewedAt      time.Time
	CorrelationID uuid.UUID
}

// RecordPostViewRepository keeps the viewing history. Record stores the row of
// (UserID, PostID) with last_viewed_at = max(stored, ViewedAt): one row per user and
// post however many views there are, and an older view delivered late never moves it
// back. Implemented by view/infrastructure/mongo.HistoryRepository.
type RecordPostViewRepository interface {
	Record(ctx context.Context, input RecordPostViewInput) error
}

// RecordPostViewCounter adds delta to the post read model's view counter in one
// atomic update and reports whether the post is projected. It never creates a post.
// Implemented by post/infrastructure/mongo.Repository.
type RecordPostViewCounter interface {
	AdjustViewCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// RecordPostViewUsecase projects a PostViewedEvent into the viewing history and the
// post's view counter.
type RecordPostViewUsecase struct {
	repository RecordPostViewRepository
	postViews  RecordPostViewCounter
	logger     *slog.Logger
}

// NewRecordPostView creates a RecordPostViewUsecase backed by repository and postViews.
func NewRecordPostView(repository RecordPostViewRepository, postViews RecordPostViewCounter, logger *slog.Logger) *RecordPostViewUsecase {
	return &RecordPostViewUsecase{repository: repository, postViews: postViews, logger: logger}
}

// Execute records the view and counts it once on the post. It runs inside the
// caller's transaction (with the idempotency check and mark), so a redelivered event
// counts nothing and a failed write counts nothing either.
//
// It does not require the post to be projected: the history read leaves out posts that
// do not exist (yet or any more), and the TTL on last_viewed_at removes rows nobody
// refreshes. Such a view is recorded in the history but counted on no post, with a
// warning: a retry could not count it either, as the post may never be projected.
func (u *RecordPostViewUsecase) Execute(ctx context.Context, input RecordPostViewInput) error {
	if err := input.validate(); err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}

	if err := u.repository.Record(ctx, input); err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}

	found, err := u.postViews.AdjustViewCount(ctx, input.PostID, 1)
	if err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "post view not counted: the post is not projected", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID)
	}

	return nil
}

func (i RecordPostViewInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}
	if i.PostID == uuid.Nil {
		return errors.New("post id is required")
	}
	if i.ViewedAt.IsZero() {
		return errors.New("viewed at is required")
	}

	return nil
}
