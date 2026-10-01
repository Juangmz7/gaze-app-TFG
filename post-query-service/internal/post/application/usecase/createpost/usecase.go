// Package createpost implements the use case that projects a PostCreatedEvent
// into the post read model.
package createpost

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly created post.
type Input struct {
	PostID      uuid.UUID
	UserID      uuid.UUID
	CollabID    uuid.UUID
	PostType    string
	Description string
	Tags        []string
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

// Repository persists the post read model. Implemented by
// post/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a PostCreatedEvent into the post read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the post read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("create post usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("create post usecase: user id is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("create post usecase: %w", err)
	}

	return nil
}
