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
	LikeID    uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// RecordLikeRepository persists the like read model. Implemented by
// like/infrastructure/mongo.Repository.
type RecordLikeRepository interface {
	Insert(ctx context.Context, input RecordLikeInput) error
}

// RecordLikeUsecase projects a PostLikeCreatedEvent into the like read
// model.
type RecordLikeUsecase struct {
	repository RecordLikeRepository
}

// NewRecordLike creates a RecordLikeUsecase backed by repository.
func NewRecordLike(repository RecordLikeRepository) *RecordLikeUsecase {
	return &RecordLikeUsecase{repository: repository}
}

// Execute inserts the like read model described by input.
func (u *RecordLikeUsecase) Execute(ctx context.Context, input RecordLikeInput) error {
	if input.LikeID == uuid.Nil {
		return fmt.Errorf("record like usecase: like id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record like usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record like usecase: user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("record like usecase: %w", err)
	}

	return nil
}
