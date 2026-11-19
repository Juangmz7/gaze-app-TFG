package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// DeleteCommentInput is the data required to remove a deleted comment's
// read model.
type DeleteCommentInput struct {
	CommentID     uuid.UUID
	PostID        uuid.UUID
	UserID        uuid.UUID
	CorrelationID uuid.UUID
}

// DeleteCommentRepository removes the comment read model and reports whether a
// document was really removed and, for a reply, the comment it replied to (uuid.Nil
// otherwise). AdjustReplyCount moves that parent's reply counter and reports whether
// the parent is projected. Implemented by comment/infrastructure/mongo.Repository.
type DeleteCommentRepository interface {
	Delete(ctx context.Context, commentID uuid.UUID) (removed bool, replyTo uuid.UUID, err error)
	AdjustReplyCount(ctx context.Context, commentID uuid.UUID, delta int64) (found bool, err error)
}

// DeleteCommentPostStats adjusts the post read model's comment counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type DeleteCommentPostStats interface {
	AdjustCommentCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// DeleteCommentUsecase projects a CommentDeletedEvent by removing the comment read
// model and decrementing the post's comment counter.
type DeleteCommentUsecase struct {
	repository DeleteCommentRepository
	postStats  DeleteCommentPostStats
	logger     *slog.Logger
}

// NewDeleteComment creates a DeleteCommentUsecase backed by repository and postStats.
func NewDeleteComment(repository DeleteCommentRepository, postStats DeleteCommentPostStats, logger *slog.Logger) *DeleteCommentUsecase {
	return &DeleteCommentUsecase{repository: repository, postStats: postStats, logger: logger}
}

// Execute deletes the comment read model described by input and, only when a
// document was really removed, decrements the post's comment counter and, for a
// reply, its parent's reply counter (neither below 0). A missing parent was deleted
// first: a warning, and the event is acked. A comment document can only exist once its post was projected, because the
// insert that created it runs in one transaction with the counter increment. So a
// missing post here means the post was deleted, not "not projected yet": there is
// no counter left to adjust and a retry could never succeed, so it logs a warning
// and acks.
func (u *DeleteCommentUsecase) Execute(ctx context.Context, input DeleteCommentInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("delete comment usecase: comment id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete comment usecase: post id is required")
	}

	removed, replyTo, err := u.repository.Delete(ctx, input.CommentID)
	if err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}
	if !removed {
		u.logger.WarnContext(ctx, "comment delete event matched no document", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID, "comment_id", input.CommentID)
		return nil
	}

	found, err := u.postStats.AdjustCommentCount(ctx, input.PostID, -1)
	if err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "comment removed for a post that is no longer projected", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID, "comment_id", input.CommentID)
	}
	if replyTo == uuid.Nil {
		return nil
	}

	parentFound, err := u.repository.AdjustReplyCount(ctx, replyTo, -1)
	if err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}
	if !parentFound {
		u.logger.WarnContext(ctx, "reply removed for a parent comment that is no longer projected", "correlation_id", input.CorrelationID, "userId", input.UserID,
			"post_id", input.PostID, "comment_id", input.CommentID, "reply_to", replyTo)
	}

	return nil
}
