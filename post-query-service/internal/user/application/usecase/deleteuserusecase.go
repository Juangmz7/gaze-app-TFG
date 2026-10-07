// Package usecase implements the use case that projects a
// UserDeletedEvent into the user read model by removing the user.
package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteUserInput is the data required to remove a deleted user's read model.
type DeleteUserInput struct {
	UserID uuid.UUID
}

// DeleteUserRepository removes the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type DeleteUserRepository interface {
	Delete(ctx context.Context, userID uuid.UUID) error
}

// DeleteUserUsecase projects a UserDeletedEvent by removing the user read model.
type DeleteUserUsecase struct {
	repository DeleteUserRepository
}

// NewDeleteUser creates a DeleteUserUsecase backed by repository.
func NewDeleteUser(repository DeleteUserRepository) *DeleteUserUsecase {
	return &DeleteUserUsecase{repository: repository}
}

// Execute deletes the user read model described by input.
func (u *DeleteUserUsecase) Execute(ctx context.Context, input DeleteUserInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete user usecase: user id is required")
	}

	if err := u.repository.Delete(ctx, input.UserID); err != nil {
		return fmt.Errorf("delete user usecase: %w", err)
	}

	return nil
}
