package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordCommentLikeInput is the data required to project a newly created comment like.
type RecordCommentLikeInput struct {
	CommentID uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// RecordCommentLikeRepository persists the comment like read model. Insert reports
// whether it really created a document. Implemented by
// comment/infrastructure/mongo.CommentLikeRepository.
type RecordCommentLikeRepository interface {
	Insert(ctx context.Context, input RecordCommentLikeInput) (inserted bool, err error)
}

// RecordCommentLikeCounter adjusts a comment's like counter and reports whether the
// comment is projected. Implemented by comment/infrastructure/mongo.Repository.
type RecordCommentLikeCounter interface {
	AdjustLikeCount(ctx context.Context, commentID uuid.UUID, delta int64) (found bool, err error)
}

// RecordCommentLikeUsecase projects a PostCommentLikeCreatedEvent into the comment
// like read model and the comment's like counter.
type RecordCommentLikeUsecase struct {
	repository RecordCommentLikeRepository
	comments   RecordCommentLikeCounter
}

// NewRecordCommentLike creates a RecordCommentLikeUsecase backed by repository and
// comments.
func NewRecordCommentLike(repository RecordCommentLikeRepository, comments RecordCommentLikeCounter) *RecordCommentLikeUsecase {
	return &RecordCommentLikeUsecase{repository: repository, comments: comments}
}

// Execute inserts the like described by input and, only when a document was really
// inserted, increments the comment's like counter, so a like delivered twice counts
// once. post-command-service only accepts a like on an active comment, so a comment
// with no projection yet is "not projected yet": a plain (retryable) error, which
// aborts the caller's transaction, so the insert is rolled back and the redelivery
// starts clean.
func (u *RecordCommentLikeUsecase) Execute(ctx context.Context, input RecordCommentLikeInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("record comment like usecase: comment id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record comment like usecase: user id is required")
	}

	inserted, err := u.repository.Insert(ctx, input)
	if err != nil {
		return fmt.Errorf("record comment like usecase: %w", err)
	}
	if !inserted {
		return nil
	}

	found, err := u.comments.AdjustLikeCount(ctx, input.CommentID, 1)
	if err != nil {
		return fmt.Errorf("record comment like usecase: %w", err)
	}
	if !found {
		return fmt.Errorf("record comment like usecase: comment %s has no projection yet", input.CommentID)
	}

	return nil
}
