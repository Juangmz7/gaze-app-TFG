// Package usecase implements the use cases that project UserFollowedEvent
// and UserUnfollowedEvent into the follow read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// CreateFollowInput is the data required to project a newly created
// follow.
type CreateFollowInput struct {
	FollowerUserID uuid.UUID
	FollowedUserID uuid.UUID
	CreatedAt      time.Time
}

// CreateFollowRepository persists the follow read model. Implemented by
// follow/infrastructure/mongo.Repository.
type CreateFollowRepository interface {
	Insert(ctx context.Context, input CreateFollowInput) error
}

// CreateFollowUsecase projects a UserFollowedEvent into the follow read
// model.
type CreateFollowUsecase struct {
	repository CreateFollowRepository
}

// NewCreateFollow creates a CreateFollowUsecase backed by repository.
func NewCreateFollow(repository CreateFollowRepository) *CreateFollowUsecase {
	return &CreateFollowUsecase{repository: repository}
}

// Execute inserts the follow read model described by input.
func (u *CreateFollowUsecase) Execute(ctx context.Context, input CreateFollowInput) error {
	if input.FollowerUserID == uuid.Nil {
		return fmt.Errorf("create follow usecase: follower user id is required")
	}
	if input.FollowedUserID == uuid.Nil {
		return fmt.Errorf("create follow usecase: followed user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("create follow usecase: %w", err)
	}

	return nil
}
