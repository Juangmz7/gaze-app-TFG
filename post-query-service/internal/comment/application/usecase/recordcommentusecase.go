// Package usecase implements the use cases that project
// PostCommentCreatedEvent, CommentUpdatedEvent, and CommentDeletedEvent
// into the comment read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordCommentInput is the data required to project a newly created
// comment.
type RecordCommentInput struct {
	CommentID uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	Content   string
	CreatedAt time.Time
}

// RecordCommentRepository persists the comment read model. Implemented by
// comment/infrastructure/mongo.Repository.
type RecordCommentRepository interface {
	Insert(ctx context.Context, input RecordCommentInput) error
}

// RecordCommentUsecase projects a PostCommentCreatedEvent into the comment
// read model.
type RecordCommentUsecase struct {
	repository RecordCommentRepository
}

// NewRecordComment creates a RecordCommentUsecase backed by repository.
func NewRecordComment(repository RecordCommentRepository) *RecordCommentUsecase {
	return &RecordCommentUsecase{repository: repository}
}

// Execute inserts the comment read model described by input.
func (u *RecordCommentUsecase) Execute(ctx context.Context, input RecordCommentInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("record comment usecase: comment id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record comment usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record comment usecase: user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("record comment usecase: %w", err)
	}

	return nil
}
