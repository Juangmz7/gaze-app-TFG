package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// DeleteCommentLikeInput is the data required to remove a deleted comment like.
// PostCommentLikeDeletedEvent carries no postId, and none is needed: the like's
// identity is (comment_id, user_id).
type DeleteCommentLikeInput struct {
	CommentID     uuid.UUID
	UserID        uuid.UUID
	CorrelationID uuid.UUID
}

// DeleteCommentLikeRepository removes the comment like read model and reports whether
// a document was really removed. Implemented by
// comment/infrastructure/mongo.CommentLikeRepository.
type DeleteCommentLikeRepository interface {
	Delete(ctx context.Context, commentID, userID uuid.UUID) (removed bool, err error)
}

// DeleteCommentLikeCounter adjusts a comment's like counter and reports whether the
// comment is projected. Implemented by comment/infrastructure/mongo.Repository.
type DeleteCommentLikeCounter interface {
	AdjustLikeCount(ctx context.Context, commentID uuid.UUID, delta int64) (found bool, err error)
}

// DeleteCommentLikeUsecase projects a PostCommentLikeDeletedEvent by removing the like
// and decrementing the comment's like counter.
type DeleteCommentLikeUsecase struct {
	repository DeleteCommentLikeRepository
	comments   DeleteCommentLikeCounter
	logger     *slog.Logger
}

// NewDeleteCommentLike creates a DeleteCommentLikeUsecase backed by repository and
// comments.
func NewDeleteCommentLike(repository DeleteCommentLikeRepository, comments DeleteCommentLikeCounter, logger *slog.Logger) *DeleteCommentLikeUsecase {
	return &DeleteCommentLikeUsecase{repository: repository, comments: comments, logger: logger}
}

// Execute deletes the like described by input and, only when a document was really
// removed, decrements the comment's like counter, never below 0. A like document
// only exists once its comment was projected, because both were written in one
// transaction, so a missing comment here was deleted since: no counter is left to
// adjust and a retry could never succeed, so it logs a warning and acks.
func (u *DeleteCommentLikeUsecase) Execute(ctx context.Context, input DeleteCommentLikeInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("delete comment like usecase: comment id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete comment like usecase: user id is required")
	}

	removed, err := u.repository.Delete(ctx, input.CommentID, input.UserID)
	if err != nil {
		return fmt.Errorf("delete comment like usecase: %w", err)
	}
	if !removed {
		u.logger.WarnContext(ctx, "comment like delete event matched no document", "correlation_id", input.CorrelationID, "userId", input.UserID, "comment_id", input.CommentID)
		return nil
	}

	found, err := u.comments.AdjustLikeCount(ctx, input.CommentID, -1)
	if err != nil {
		return fmt.Errorf("delete comment like usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "comment like removed for a comment that is no longer projected", "correlation_id", input.CorrelationID, "userId", input.UserID, "comment_id", input.CommentID)
	}

	return nil
}
