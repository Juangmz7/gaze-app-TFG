// Package usecase implements the use case that projects a
// PostShareCreatedEvent into the share read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly created post share.
type Input struct {
	ShareID   uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// Repository persists the share read model. Implemented by
// share/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a PostShareCreatedEvent into the share read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the share read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.ShareID == uuid.Nil {
		return fmt.Errorf("record share usecase: share id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record share usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record share usecase: user id is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("record share usecase: %w", err)
	}

	return nil
}
