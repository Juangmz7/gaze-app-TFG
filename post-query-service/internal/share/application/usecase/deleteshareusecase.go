package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteShareInput is the data required to remove a deleted share's read
// model.
//
// PostShareDeletedEvent (verified against the real Java record) carries no
// shareId: only id, correlationId, occurredAt, postId, and userId. The same
// reasoning documented on like's usecase.DeleteLikeInput applies here:
// PostShareCreatedEvent's handler derives ShareID purely from the creation
// event's own shareId field, and the share read model's unique index is on
// share_id alone, not on (post_id, user_id). On the justified assumption
// that this domain models one active share per (post, user) at a time,
// this usecase deletes by PostID+UserID. If post-command-service's domain
// ever allows multiple concurrent share records per (post, user), this
// filter would need a real shareId, which PostShareDeletedEvent would need
// to start carrying — see DeleteLikeInput's doc comment for the full
// reasoning, identical here.
type DeleteShareInput struct {
	PostID uuid.UUID
	UserID uuid.UUID
}

// DeleteShareRepository removes the share read model document matching
// PostID+UserID. Implemented by share/infrastructure/mongo.Repository.
type DeleteShareRepository interface {
	DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) error
}

// DeleteShareUsecase projects a PostShareDeletedEvent by removing the share
// read model identified by PostID+UserID (see DeleteShareInput's doc
// comment for why there is no shareId to delete by).
type DeleteShareUsecase struct {
	repository DeleteShareRepository
}

// NewDeleteShare creates a DeleteShareUsecase backed by repository.
func NewDeleteShare(repository DeleteShareRepository) *DeleteShareUsecase {
	return &DeleteShareUsecase{repository: repository}
}

// Execute deletes the share read model described by input.
func (u *DeleteShareUsecase) Execute(ctx context.Context, input DeleteShareInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete share usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete share usecase: user id is required")
	}

	if err := u.repository.DeleteByPostAndUser(ctx, input.PostID, input.UserID); err != nil {
		return fmt.Errorf("delete share usecase: %w", err)
	}

	return nil
}
