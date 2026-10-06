// Package usecase implements the use cases that project PostCreatedEvent,
// CollabLinkedEvent, PostUpdatedEvent, and PostDeletedEvent into the post
// read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// CreatePostInput is the data required to project a newly created post.
type CreatePostInput struct {
	PostID      uuid.UUID
	UserID      uuid.UUID
	CollabID    uuid.UUID
	PostType    string
	Description string
	Tags        []string
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

// CreatePostRepository persists the post read model. Implemented by
// post/infrastructure/mongo.Repository.
type CreatePostRepository interface {
	Insert(ctx context.Context, input CreatePostInput) error
}

// CreatePostUsecase projects a PostCreatedEvent (or a CollabLinkedEvent,
// which is semantically also a post creation) into the post read model.
type CreatePostUsecase struct {
	repository CreatePostRepository
}

// NewCreatePost creates a CreatePostUsecase backed by repository.
func NewCreatePost(repository CreatePostRepository) *CreatePostUsecase {
	return &CreatePostUsecase{repository: repository}
}

// Execute inserts the post read model described by input.
func (u *CreatePostUsecase) Execute(ctx context.Context, input CreatePostInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("create post usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("create post usecase: user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("create post usecase: %w", err)
	}

	return nil
}
