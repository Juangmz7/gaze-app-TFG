package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteFollowInput is the data required to remove a deleted follow's read
// model.
type DeleteFollowInput struct {
	FollowerUserID uuid.UUID
	FollowedUserID uuid.UUID
}

// DeleteFollowRepository removes the follow read model. Implemented by
// follow/infrastructure/mongo.Repository.
type DeleteFollowRepository interface {
	Delete(ctx context.Context, followerUserID, followedUserID uuid.UUID) error
}

// DeleteFollowUsecase projects a UserUnfollowedEvent by removing the
// follow read model.
type DeleteFollowUsecase struct {
	repository DeleteFollowRepository
}

// NewDeleteFollow creates a DeleteFollowUsecase backed by repository.
func NewDeleteFollow(repository DeleteFollowRepository) *DeleteFollowUsecase {
	return &DeleteFollowUsecase{repository: repository}
}

// Execute deletes the follow read model described by input.
func (u *DeleteFollowUsecase) Execute(ctx context.Context, input DeleteFollowInput) error {
	if input.FollowerUserID == uuid.Nil {
		return fmt.Errorf("delete follow usecase: follower user id is required")
	}
	if input.FollowedUserID == uuid.Nil {
		return fmt.Errorf("delete follow usecase: followed user id is required")
	}

	if err := u.repository.Delete(ctx, input.FollowerUserID, input.FollowedUserID); err != nil {
		return fmt.Errorf("delete follow usecase: %w", err)
	}

	return nil
}
