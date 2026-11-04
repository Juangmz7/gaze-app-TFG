package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeletePostInput is the data required to remove a deleted post's read
// model.
type DeletePostInput struct {
	PostID uuid.UUID
}

// DeletePostRepository removes the post read model. Implemented by
// post/infrastructure/mongo.Repository.
type DeletePostRepository interface {
	Delete(ctx context.Context, postID uuid.UUID) error
}

// DeletePostFeedRepository removes a deleted post from every feed. Implemented by
// feed/infrastructure/mongo.FeedUnitRepository.
type DeletePostFeedRepository interface {
	DeleteByPost(ctx context.Context, postID uuid.UUID) error
}

// DeletePostUsecase projects a PostDeletedEvent by removing the post read
// model and its feed units.
type DeletePostUsecase struct {
	repository DeletePostRepository
	feedUnits  DeletePostFeedRepository
}

// NewDeletePost creates a DeletePostUsecase backed by repository and feedUnits.
func NewDeletePost(repository DeletePostRepository, feedUnits DeletePostFeedRepository) *DeletePostUsecase {
	return &DeletePostUsecase{repository: repository, feedUnits: feedUnits}
}

// Execute deletes the post read model described by input and its feed units. Both
// deletes are idempotent, so a redelivery after a partial failure finishes the work.
func (u *DeletePostUsecase) Execute(ctx context.Context, input DeletePostInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("delete post usecase: post id is required")
	}

	if err := u.repository.Delete(ctx, input.PostID); err != nil {
		return fmt.Errorf("delete post usecase: %w", err)
	}

	if err := u.feedUnits.DeleteByPost(ctx, input.PostID); err != nil {
		return fmt.Errorf("delete post usecase: %w", err)
	}

	return nil
}
