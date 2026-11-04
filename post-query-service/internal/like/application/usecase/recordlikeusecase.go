// Package usecase implements the use cases that project
// PostLikeCreatedEvent and PostLikeDeletedEvent into the like read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordLikeInput is the data required to project a newly created post
// like.
type RecordLikeInput struct {
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// RecordLikeRepository persists the like read model. Insert reports whether it
// really created a document. Implemented by like/infrastructure/mongo.Repository.
type RecordLikeRepository interface {
	Insert(ctx context.Context, input RecordLikeInput) (inserted bool, err error)
}

// RecordLikePostStats adjusts the post read model's like counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type RecordLikePostStats interface {
	AdjustLikeCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// RecordLikeUsecase projects a PostLikeCreatedEvent into the like read
// model.
type RecordLikeUsecase struct {
	repository RecordLikeRepository
	postStats  RecordLikePostStats
}

// NewRecordLike creates a RecordLikeUsecase backed by repository and postStats.
func NewRecordLike(repository RecordLikeRepository, postStats RecordLikePostStats) *RecordLikeUsecase {
	return &RecordLikeUsecase{repository: repository, postStats: postStats}
}

// Execute inserts the like read model described by input and, only when a
// document was really inserted, increments the post's like counter. A post with
// no projection yet yields a plain (retryable) error, which aborts the caller's
// transaction, so the insert is rolled back and the redelivery starts clean.
func (u *RecordLikeUsecase) Execute(ctx context.Context, input RecordLikeInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record like usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record like usecase: user id is required")
	}

	inserted, err := u.repository.Insert(ctx, input)
	if err != nil {
		return fmt.Errorf("record like usecase: %w", err)
	}
	if !inserted {
		return nil
	}

	found, err := u.postStats.AdjustLikeCount(ctx, input.PostID, 1)
	if err != nil {
		return fmt.Errorf("record like usecase: %w", err)
	}
	if !found {
		return fmt.Errorf("record like usecase: post %s has no projection yet", input.PostID)
	}

	return nil
}
