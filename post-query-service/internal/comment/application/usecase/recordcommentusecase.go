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
	ReplyTo   uuid.UUID
	CreatedAt time.Time
	UpdatedAt time.Time
}

// RecordCommentRepository persists the comment read model. Insert reports whether
// it really created a document. Implemented by
// comment/infrastructure/mongo.Repository.
type RecordCommentRepository interface {
	Insert(ctx context.Context, input RecordCommentInput) (inserted bool, err error)
}

// RecordCommentPostStats adjusts the post read model's comment counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type RecordCommentPostStats interface {
	AdjustCommentCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// RecordCommentUsecase projects a PostCommentCreatedEvent into the comment
// read model.
type RecordCommentUsecase struct {
	repository RecordCommentRepository
	postStats  RecordCommentPostStats
}

// NewRecordComment creates a RecordCommentUsecase backed by repository and postStats.
func NewRecordComment(repository RecordCommentRepository, postStats RecordCommentPostStats) *RecordCommentUsecase {
	return &RecordCommentUsecase{repository: repository, postStats: postStats}
}

// Execute inserts the comment read model described by input and, only when a
// document was really inserted, increments the post's comment counter. A post with
// no projection yet yields a plain (retryable) error, which aborts the caller's
// transaction, so the insert is rolled back and the redelivery starts clean.
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

	inserted, err := u.repository.Insert(ctx, input)
	if err != nil {
		return fmt.Errorf("record comment usecase: %w", err)
	}
	if !inserted {
		return nil
	}

	found, err := u.postStats.AdjustCommentCount(ctx, input.PostID, 1)
	if err != nil {
		return fmt.Errorf("record comment usecase: %w", err)
	}
	if !found {
		return fmt.Errorf("record comment usecase: post %s has no projection yet", input.PostID)
	}

	return nil
}
