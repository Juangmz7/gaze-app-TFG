// Package usecase implements the use cases that project UserBlockedEvent and
// UserUnblockedEvent into the block read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// CreateBlockInput is the data required to project a newly created block.
type CreateBlockInput struct {
	BlockerUserID uuid.UUID
	BlockedUserID uuid.UUID
	CreatedAt     time.Time
}

// CreateBlockRepository persists the block read model. Implemented by
// block/infrastructure/mongo.Repository.
type CreateBlockRepository interface {
	Insert(ctx context.Context, input CreateBlockInput) error
}

// CreateBlockUsecase projects a UserBlockedEvent into the block read model.
type CreateBlockUsecase struct {
	repository CreateBlockRepository
}

// NewCreateBlock creates a CreateBlockUsecase backed by repository.
func NewCreateBlock(repository CreateBlockRepository) *CreateBlockUsecase {
	return &CreateBlockUsecase{repository: repository}
}

// Execute inserts the block read model described by input.
func (u *CreateBlockUsecase) Execute(ctx context.Context, input CreateBlockInput) error {
	if input.BlockerUserID == uuid.Nil {
		return fmt.Errorf("create block usecase: blocker user id is required")
	}
	if input.BlockedUserID == uuid.Nil {
		return fmt.Errorf("create block usecase: blocked user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("create block usecase: %w", err)
	}

	return nil
}
