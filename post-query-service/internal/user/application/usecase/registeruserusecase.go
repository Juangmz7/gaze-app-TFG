// Package usecase implements the use case that projects a
// UserRegisteredEvent into the user read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RegisterUserInput is the data required to project a newly registered user.
type RegisterUserInput struct {
	UserID    uuid.UUID
	Username  string
	CreatedAt time.Time
}

// RegisterUserRepository persists the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type RegisterUserRepository interface {
	Upsert(ctx context.Context, input RegisterUserInput) error
}

// RegisterUserUsecase projects a UserRegisteredEvent into the user read model.
type RegisterUserUsecase struct {
	repository RegisterUserRepository
}

// NewRegisterUser creates a RegisterUserUsecase backed by repository.
func NewRegisterUser(repository RegisterUserRepository) *RegisterUserUsecase {
	return &RegisterUserUsecase{repository: repository}
}

// Execute upserts the user read model described by input.
func (u *RegisterUserUsecase) Execute(ctx context.Context, input RegisterUserInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("register user usecase: user id is required")
	}
	if input.Username == "" {
		return fmt.Errorf("register user usecase: username is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("register user usecase: %w", err)
	}

	return nil
}
