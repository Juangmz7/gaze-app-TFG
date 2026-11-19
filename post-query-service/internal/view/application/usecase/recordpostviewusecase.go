// Package usecase implements the use case that projects PostViewedEvent into the
// viewing history read model: one row per (user, post), holding the latest view.
package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordPostViewInput is one view of a post by a user, at ViewedAt (the event's
// serverTimestamp, set by post-command-service when it accepted the view).
type RecordPostViewInput struct {
	UserID   uuid.UUID
	PostID   uuid.UUID
	ViewedAt time.Time
}

// RecordPostViewRepository keeps the viewing history. Record stores the row of
// (UserID, PostID) with last_viewed_at = max(stored, ViewedAt): one row per user and
// post however many views there are, and an older view delivered late never moves it
// back. Implemented by view/infrastructure/mongo.HistoryRepository.
type RecordPostViewRepository interface {
	Record(ctx context.Context, input RecordPostViewInput) error
}

// RecordPostViewUsecase projects a PostViewedEvent into the viewing history.
type RecordPostViewUsecase struct {
	repository RecordPostViewRepository
}

// NewRecordPostView creates a RecordPostViewUsecase backed by repository.
func NewRecordPostView(repository RecordPostViewRepository) *RecordPostViewUsecase {
	return &RecordPostViewUsecase{repository: repository}
}

// Execute records the view. It does not require the post to be projected: the history
// read leaves out posts that do not exist (yet or any more), and the TTL on
// last_viewed_at removes rows nobody refreshes.
func (u *RecordPostViewUsecase) Execute(ctx context.Context, input RecordPostViewInput) error {
	if err := input.validate(); err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}

	if err := u.repository.Record(ctx, input); err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
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
