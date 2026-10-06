package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeletePostInput is the data required to remove a deleted post's read
// model.
type DeletePostInput struct {
	PostID uuid.UUID
}

// DeletePostRepository removes the post read model. Implemented by
// post/infrastructure/mongo.Repository.
type DeletePostRepository interface {
	Delete(ctx context.Context, postID uuid.UUID) error
}

// DeletePostUsecase projects a PostDeletedEvent by removing the post read
// model.
type DeletePostUsecase struct {
	repository DeletePostRepository
}

// NewDeletePost creates a DeletePostUsecase backed by repository.
func NewDeletePost(repository DeletePostRepository) *DeletePostUsecase {
	return &DeletePostUsecase{repository: repository}
}

// Execute deletes the post read model described by input.
func (u *DeletePostUsecase) Execute(ctx context.Context, input DeletePostInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete post usecase: post id is required")
	}

	if err := u.repository.Delete(ctx, input.PostID); err != nil {
		return fmt.Errorf("delete post usecase: %w", err)
	}

	return nil
}
