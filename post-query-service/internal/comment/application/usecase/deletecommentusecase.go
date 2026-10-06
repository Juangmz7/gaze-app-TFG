package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteCommentInput is the data required to remove a deleted comment's
// read model.
type DeleteCommentInput struct {
	CommentID uuid.UUID
}

// DeleteCommentRepository removes the comment read model. Implemented by
// comment/infrastructure/mongo.Repository.
type DeleteCommentRepository interface {
	Delete(ctx context.Context, commentID uuid.UUID) error
}

// DeleteCommentUsecase projects a CommentDeletedEvent by removing the
// comment read model.
type DeleteCommentUsecase struct {
	repository DeleteCommentRepository
}

// NewDeleteComment creates a DeleteCommentUsecase backed by repository.
func NewDeleteComment(repository DeleteCommentRepository) *DeleteCommentUsecase {
	return &DeleteCommentUsecase{repository: repository}
}

// Execute deletes the comment read model described by input.
func (u *DeleteCommentUsecase) Execute(ctx context.Context, input DeleteCommentInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("delete comment usecase: comment id is required")
	}

	if err := u.repository.Delete(ctx, input.CommentID); err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}

	return nil
}
