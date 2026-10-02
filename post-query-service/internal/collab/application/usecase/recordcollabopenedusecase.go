// Package usecase implements the use case that projects a
// PostCollabOpenedEvent (post-created variant) into the collab read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly opened collaboration.
type Input struct {
	CollabID    uuid.UUID
	PostID      uuid.UUID
	OwnerUserID uuid.UUID
	CreatedAt   time.Time
}

// Repository persists the collab read model. Implemented by
// collab/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a PostCollabOpenedEvent into the collab read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the collab read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.CollabID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: collab id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: post id is required")
	}
	if input.OwnerUserID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: owner user id is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("record collab opened usecase: %w", err)
	}

	return nil
}
