// Package usecase implements the use cases that project
// PostShareCreatedEvent and PostShareDeletedEvent into the share read
// model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordShareInput is the data required to project a newly created post
// share.
type RecordShareInput struct {
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// RecordShareRepository persists the share read model. Insert reports whether it
// really created a document. Implemented by share/infrastructure/mongo.Repository.
type RecordShareRepository interface {
	Insert(ctx context.Context, input RecordShareInput) (inserted bool, err error)
}

// RecordSharePostStats adjusts the post read model's share counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type RecordSharePostStats interface {
	AdjustShareCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// RecordShareUsecase projects a PostShareCreatedEvent into the share read
// model.
type RecordShareUsecase struct {
	repository RecordShareRepository
	postStats  RecordSharePostStats
}

// NewRecordShare creates a RecordShareUsecase backed by repository and postStats.
func NewRecordShare(repository RecordShareRepository, postStats RecordSharePostStats) *RecordShareUsecase {
	return &RecordShareUsecase{repository: repository, postStats: postStats}
}

// Execute inserts the share read model described by input and, only when a
// document was really inserted, increments the post's share counter. A post with
// no projection yet yields a plain (retryable) error, which aborts the caller's
// transaction, so the insert is rolled back and the redelivery starts clean.
func (u *RecordShareUsecase) Execute(ctx context.Context, input RecordShareInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record share usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record share usecase: user id is required")
	}

	inserted, err := u.repository.Insert(ctx, input)
	if err != nil {
		return fmt.Errorf("record share usecase: %w", err)
	}
	if !inserted {
		return nil
	}

	found, err := u.postStats.AdjustShareCount(ctx, input.PostID, 1)
	if err != nil {
		return fmt.Errorf("record share usecase: %w", err)
	}
	if !found {
		return fmt.Errorf("record share usecase: post %s has no projection yet", input.PostID)
	}

	return nil
}
