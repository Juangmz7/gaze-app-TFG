package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteLikeInput is the data required to remove a deleted like's read
// model.
//
// PostLikeDeletedEvent (verified against the real Java record) carries no
// likeId at all: only postId, userId, source, and feedPosition. It cannot
// be resolved to a specific like document by business id the way every
// other delete event in this task can. The read model's unique index is on
// like_id alone (added by a prior task), not on (post_id, user_id), so
// there is no database-enforced guarantee that at most one like document
// exists per (post_id, user_id) pair.
//
// Decision: this usecase deletes by PostID+UserID rather than by LikeID,
// on the justified assumption that the social-feed "like" domain models
// one active like per (post, user) at a time — PostLikeCreatedEvent's own
// handler (postlikecreatedhandler.go) derives LikeID purely from the
// creation event's own likeId field and never reads it back from a
// deletion event, and post-command-service's domain does not expose a way
// to have two simultaneously-active likes by the same user on the same
// post. Under that assumption, deleting by PostID+UserID removes exactly
// the one live like document for that pair. This is a best-effort decision
// documented as a known data-modeling gap in the upstream event, not a
// guess papered over as correct: if post-command-service's domain ever
// allows multiple concurrent like records per (post, user) (e.g. a
// history of toggles kept as separate documents), this filter would need
// to change to use a real like_id, which PostLikeDeletedEvent would need
// to start carrying.
type DeleteLikeInput struct {
	PostID uuid.UUID
	UserID uuid.UUID
}

// DeleteLikeRepository removes the like read model document matching
// PostID+UserID. Implemented by like/infrastructure/mongo.Repository.
type DeleteLikeRepository interface {
	DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) error
}

// DeleteLikeUsecase projects a PostLikeDeletedEvent by removing the like
// read model identified by PostID+UserID (see DeleteLikeInput's doc
// comment for why there is no likeId to delete by).
type DeleteLikeUsecase struct {
	repository DeleteLikeRepository
}

// NewDeleteLike creates a DeleteLikeUsecase backed by repository.
func NewDeleteLike(repository DeleteLikeRepository) *DeleteLikeUsecase {
	return &DeleteLikeUsecase{repository: repository}
}

// Execute deletes the like read model described by input.
func (u *DeleteLikeUsecase) Execute(ctx context.Context, input DeleteLikeInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete like usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete like usecase: user id is required")
	}

	if err := u.repository.DeleteByPostAndUser(ctx, input.PostID, input.UserID); err != nil {
		return fmt.Errorf("delete like usecase: %w", err)
	}

	return nil
}
