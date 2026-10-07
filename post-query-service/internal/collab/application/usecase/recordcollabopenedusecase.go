// Package usecase implements the use cases that project
// PostCollabOpenedEvent and CollabClosedEvent into the collab read model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordCollabOpenedInput is the data required to project a newly opened
// collaboration.
type RecordCollabOpenedInput struct {
	CollabID    uuid.UUID
	PostID      uuid.UUID
	OwnerUserID uuid.UUID
	CreatedAt   time.Time
}

// RecordCollabOpenedRepository persists the collab read model. Implemented
// by collab/infrastructure/mongo.Repository.
type RecordCollabOpenedRepository interface {
	Insert(ctx context.Context, input RecordCollabOpenedInput) error
}

// RecordCollabOpenedUsecase projects a PostCollabOpenedEvent into the
// collab read model.
type RecordCollabOpenedUsecase struct {
	repository RecordCollabOpenedRepository
}

// NewRecordCollabOpened creates a RecordCollabOpenedUsecase backed by
// repository.
func NewRecordCollabOpened(repository RecordCollabOpenedRepository) *RecordCollabOpenedUsecase {
	return &RecordCollabOpenedUsecase{repository: repository}
}

// Execute inserts the collab read model described by input.
func (u *RecordCollabOpenedUsecase) Execute(ctx context.Context, input RecordCollabOpenedInput) error {
	if input.CollabID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: collab id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: post id is required")
	}
	if input.OwnerUserID == uuid.Nil {
		return fmt.Errorf("record collab opened usecase: owner user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("record collab opened usecase: %w", err)
	}

	return nil
}
