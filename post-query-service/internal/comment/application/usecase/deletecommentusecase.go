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
	CommentID uuid.UUID
	PostID    uuid.UUID
}

// DeleteCommentRepository removes the comment read model and reports whether a
// document was really removed. Implemented by comment/infrastructure/mongo.Repository.
type DeleteCommentRepository interface {
	Delete(ctx context.Context, commentID uuid.UUID) (removed bool, err error)
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
// document was really removed, decrements the post's comment counter (never below
// 0). A comment document can only exist once its post was projected, because the
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

	removed, err := u.repository.Delete(ctx, input.CommentID)
	if err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}
	if !removed {
		return nil
	}

	found, err := u.postStats.AdjustCommentCount(ctx, input.PostID, -1)
	if err != nil {
		return fmt.Errorf("delete comment usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "comment removed for a post that is no longer projected", "post_id", input.PostID, "comment_id", input.CommentID)
	}

	return nil
}
