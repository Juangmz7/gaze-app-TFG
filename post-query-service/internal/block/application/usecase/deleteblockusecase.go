package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteBlockInput is the data required to remove a deleted block's read
// model.
type DeleteBlockInput struct {
	BlockerUserID uuid.UUID
	BlockedUserID uuid.UUID
}

// DeleteBlockRepository removes the block read model. Implemented by
// block/infrastructure/mongo.Repository.
type DeleteBlockRepository interface {
	Delete(ctx context.Context, blockerUserID, blockedUserID uuid.UUID) error
}

// DeleteBlockUsecase projects a UserUnblockedEvent by removing the block
// read model.
type DeleteBlockUsecase struct {
	repository DeleteBlockRepository
}

// NewDeleteBlock creates a DeleteBlockUsecase backed by repository.
func NewDeleteBlock(repository DeleteBlockRepository) *DeleteBlockUsecase {
	return &DeleteBlockUsecase{repository: repository}
}

// Execute deletes the block read model described by input.
func (u *DeleteBlockUsecase) Execute(ctx context.Context, input DeleteBlockInput) error {
	if input.BlockerUserID == uuid.Nil {
		return fmt.Errorf("delete block usecase: blocker user id is required")
	}
	if input.BlockedUserID == uuid.Nil {
		return fmt.Errorf("delete block usecase: blocked user id is required")
	}

	if err := u.repository.Delete(ctx, input.BlockerUserID, input.BlockedUserID); err != nil {
		return fmt.Errorf("delete block usecase: %w", err)
	}

	return nil
}
