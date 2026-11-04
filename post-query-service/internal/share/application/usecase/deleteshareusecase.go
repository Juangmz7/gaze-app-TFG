package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// DeleteShareInput is the data required to remove a deleted share's read model.
// (PostID, UserID) is the share's identity: neither the created nor the deleted
// event carries a shareId, and post-command-service uses that pair as the primary
// key of post_shares.
type DeleteShareInput struct {
	PostID        uuid.UUID
	UserID        uuid.UUID
	CorrelationID uuid.UUID
}

// DeleteShareRepository removes the share read model document matching
// PostID+UserID and reports whether a document was really removed. Implemented
// by share/infrastructure/mongo.Repository.
type DeleteShareRepository interface {
	DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) (removed bool, err error)
}

// DeleteSharePostStats adjusts the post read model's share counter and reports
// whether the post is projected. Implemented by post/infrastructure/mongo.Repository.
type DeleteSharePostStats interface {
	AdjustShareCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// DeleteShareUsecase projects a PostShareDeletedEvent by removing the share read
// model identified by PostID+UserID and decrementing the post's share counter.
type DeleteShareUsecase struct {
	repository DeleteShareRepository
	postStats  DeleteSharePostStats
	logger     *slog.Logger
}

// NewDeleteShare creates a DeleteShareUsecase backed by repository and postStats.
func NewDeleteShare(repository DeleteShareRepository, postStats DeleteSharePostStats, logger *slog.Logger) *DeleteShareUsecase {
	return &DeleteShareUsecase{repository: repository, postStats: postStats, logger: logger}
}

// Execute deletes the share read model described by input and, only when a
// document was really removed, decrements the post's share counter (never below
// 0). A share document can only exist once its post was projected, because the
// insert that created it runs in one transaction with the counter increment. So
// a missing post here means the post was deleted, not "not projected yet": there
// is no counter left to adjust and a retry could never succeed, so it logs a
// warning and acks.
func (u *DeleteShareUsecase) Execute(ctx context.Context, input DeleteShareInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete share usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete share usecase: user id is required")
	}

	removed, err := u.repository.DeleteByPostAndUser(ctx, input.PostID, input.UserID)
	if err != nil {
		return fmt.Errorf("delete share usecase: %w", err)
	}
	if !removed {
		u.logger.WarnContext(ctx, "share delete event matched no document", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID)
		return nil
	}

	found, err := u.postStats.AdjustShareCount(ctx, input.PostID, -1)
	if err != nil {
		return fmt.Errorf("delete share usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "share removed for a post that is no longer projected", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID)
	}

	return nil
}
