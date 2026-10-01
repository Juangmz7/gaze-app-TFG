// Package registeruser implements the use case that projects a
// UserRegisteredEvent into the user read model.
package registeruser

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly registered user.
type Input struct {
	UserID    uuid.UUID
	Username  string
	CreatedAt time.Time
}

// Repository persists the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a UserRegisteredEvent into the user read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the user read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
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
