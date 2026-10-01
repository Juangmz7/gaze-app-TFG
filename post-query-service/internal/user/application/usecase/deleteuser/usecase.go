// Package deleteuser implements the use case that projects a
// UserDeletedEvent into the user read model by removing the user.
package deleteuser

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// Input is the data required to remove a deleted user's read model.
type Input struct {
	UserID uuid.UUID
}

// Repository removes the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type Repository interface {
	Delete(ctx context.Context, userID uuid.UUID) error
}

// Usecase projects a UserDeletedEvent by removing the user read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute deletes the user read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete user usecase: user id is required")
	}

	if err := u.repository.Delete(ctx, input.UserID); err != nil {
		return fmt.Errorf("delete user usecase: %w", err)
	}

	return nil
}
