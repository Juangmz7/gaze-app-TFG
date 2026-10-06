package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// DeleteCollabInput is the data required to remove a deleted collab's read
// model.
type DeleteCollabInput struct {
	CollabID uuid.UUID
}

// DeleteCollabRepository removes the collab read model. Implemented by
// collab/infrastructure/mongo.Repository.
type DeleteCollabRepository interface {
	Delete(ctx context.Context, collabID uuid.UUID) error
}

// DeleteCollabUsecase projects a CollabDeletedEvent by removing the collab
// read model.
type DeleteCollabUsecase struct {
	repository DeleteCollabRepository
}

// NewDeleteCollab creates a DeleteCollabUsecase backed by repository.
func NewDeleteCollab(repository DeleteCollabRepository) *DeleteCollabUsecase {
	return &DeleteCollabUsecase{repository: repository}
}

// Execute deletes the collab read model described by input.
func (u *DeleteCollabUsecase) Execute(ctx context.Context, input DeleteCollabInput) error {
	if input.CollabID == uuid.Nil {
		return fmt.Errorf("delete collab usecase: collab id is required")
	}

	if err := u.repository.Delete(ctx, input.CollabID); err != nil {
		return fmt.Errorf("delete collab usecase: %w", err)
	}

	return nil
}
