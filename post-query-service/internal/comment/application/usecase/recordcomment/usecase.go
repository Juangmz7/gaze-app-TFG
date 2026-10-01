// Package recordcomment implements the use case that projects a
// PostCommentCreatedEvent into the comment read model.
package recordcomment

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Input is the data required to project a newly created comment.
type Input struct {
	CommentID uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	Content   string
	CreatedAt time.Time
}

// Repository persists the comment read model. Implemented by
// comment/infrastructure/mongo.Repository.
type Repository interface {
	Upsert(ctx context.Context, input Input) error
}

// Usecase projects a PostCommentCreatedEvent into the comment read model.
type Usecase struct {
	repository Repository
}

// New creates a Usecase backed by repository.
func New(repository Repository) *Usecase {
	return &Usecase{repository: repository}
}

// Execute upserts the comment read model described by input.
func (u *Usecase) Execute(ctx context.Context, input Input) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("record comment usecase: comment id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record comment usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record comment usecase: user id is required")
	}

	if err := u.repository.Upsert(ctx, input); err != nil {
		return fmt.Errorf("record comment usecase: %w", err)
	}

	return nil
}
