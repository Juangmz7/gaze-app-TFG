// Package usecase implements the use case that projects a
// PostLikeCreatedEvent into the like read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly created post like.
type Input struct {
	LikeID    uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// Repository persists the like read model. Implemented by
// like/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a PostLikeCreatedEvent into the like read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the like read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.LikeID == uuid.Nil {
		return fmt.Errorf("record like usecase: like id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record like usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record like usecase: user id is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("record like usecase: %w", err)
	}

	return nil
}
