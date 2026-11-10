// Package usecase implements the use case that projects a
// UserDeletedEvent into the user read model by removing the user.
package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteUserInput is the data required to remove a deleted user's read model.
type DeleteUserInput struct {
	UserID uuid.UUID
}

// DeleteUserRepository removes the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type DeleteUserRepository interface {
	Delete(ctx context.Context, userID uuid.UUID) error
}

// DeleteUserFeedSessionRepository removes the user's presence document. Implemented
// by feed/infrastructure/mongo.SessionRepository.
type DeleteUserFeedSessionRepository interface {
	DeleteByUser(ctx context.Context, userID uuid.UUID) error
}

// DeleteUserFeedUnitRepository removes the user's feed and the units of their posts in
// other feeds. Implemented by feed/infrastructure/mongo.FeedUnitRepository.
type DeleteUserFeedUnitRepository interface {
	DeleteByUserOrCreator(ctx context.Context, userID uuid.UUID) error
}

// DeleteUserUsecase projects a UserDeletedEvent by removing the user read model, their
// presence document, their feed units and the units whose creator they are.
type DeleteUserUsecase struct {
	repository   DeleteUserRepository
	feedSessions DeleteUserFeedSessionRepository
	feedUnits    DeleteUserFeedUnitRepository
}

// NewDeleteUser creates a DeleteUserUsecase backed by repository, feedSessions and
// feedUnits.
func NewDeleteUser(repository DeleteUserRepository, feedSessions DeleteUserFeedSessionRepository, feedUnits DeleteUserFeedUnitRepository) *DeleteUserUsecase {
	return &DeleteUserUsecase{repository: repository, feedSessions: feedSessions, feedUnits: feedUnits}
}

// Execute deletes everything held about the user described by input. Every delete is
// idempotent, so a redelivery after a partial failure finishes the work.
func (u *DeleteUserUsecase) Execute(ctx context.Context, input DeleteUserInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("delete user usecase: user id is required")
	}

	if err := u.repository.Delete(ctx, input.UserID); err != nil {
		return fmt.Errorf("delete user usecase: %w", err)
	}

	if err := u.feedSessions.DeleteByUser(ctx, input.UserID); err != nil {
		return fmt.Errorf("delete user usecase: %w", err)
	}

	if err := u.feedUnits.DeleteByUserOrCreator(ctx, input.UserID); err != nil {
		return fmt.Errorf("delete user usecase: %w", err)
	}

	return nil
}
