package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// DeleteLikeInput is the data required to remove a deleted like's read model.
// (PostID, UserID) is the like's identity: neither the created nor the deleted
// event carries a likeId, and post-command-service uses that pair as the primary
// key of post_likes.
type DeleteLikeInput struct {
	PostID uuid.UUID
	UserID uuid.UUID
}

// DeleteLikeRepository removes the like read model document matching
// PostID+UserID and reports whether a document was really removed. Implemented
// by like/infrastructure/mongo.Repository.
type DeleteLikeRepository interface {
	DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) (removed bool, err error)
}

// DeleteLikePostStats adjusts the post read model's like counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type DeleteLikePostStats interface {
	AdjustLikeCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// DeleteLikeUsecase projects a PostLikeDeletedEvent by removing the like read
// model identified by PostID+UserID and decrementing the post's like counter.
type DeleteLikeUsecase struct {
	repository DeleteLikeRepository
	postStats  DeleteLikePostStats
	logger     *slog.Logger
}

// NewDeleteLike creates a DeleteLikeUsecase backed by repository and postStats.
func NewDeleteLike(repository DeleteLikeRepository, postStats DeleteLikePostStats, logger *slog.Logger) *DeleteLikeUsecase {
	return &DeleteLikeUsecase{repository: repository, postStats: postStats, logger: logger}
}

// Execute deletes the like read model described by input and, only when a
// document was really removed, decrements the post's like counter (never below
// 0). A like document can only exist once its post was projected, because the
// insert that created it runs in one transaction with the counter increment. So
// a missing post here means the post was deleted, not "not projected yet": there
// is no counter left to adjust and a retry could never succeed, so it logs a
// warning and acks.
func (u *DeleteLikeUsecase) Execute(ctx context.Context, input DeleteLikeInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete like usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete like usecase: user id is required")
	}

	removed, err := u.repository.DeleteByPostAndUser(ctx, input.PostID, input.UserID)
	if err != nil {
		return fmt.Errorf("delete like usecase: %w", err)
	}
	if !removed {
		return nil
	}

	found, err := u.postStats.AdjustLikeCount(ctx, input.PostID, -1)
	if err != nil {
		return fmt.Errorf("delete like usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "like removed for a post that is no longer projected", "post_id", input.PostID, "user_id", input.UserID)
	}

	return nil
}
