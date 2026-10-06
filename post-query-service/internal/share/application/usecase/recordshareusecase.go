// Package usecase implements the use cases that project
// PostShareCreatedEvent and PostShareDeletedEvent into the share read
// model.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RecordShareInput is the data required to project a newly created post
// share.
type RecordShareInput struct {
	ShareID   uuid.UUID
	PostID    uuid.UUID
	UserID    uuid.UUID
	CreatedAt time.Time
}

// RecordShareRepository persists the share read model. Implemented by
// share/infrastructure/mongo.Repository.
type RecordShareRepository interface {
	Insert(ctx context.Context, input RecordShareInput) error
}

// RecordShareUsecase projects a PostShareCreatedEvent into the share read
// model.
type RecordShareUsecase struct {
	repository RecordShareRepository
}

// NewRecordShare creates a RecordShareUsecase backed by repository.
func NewRecordShare(repository RecordShareRepository) *RecordShareUsecase {
	return &RecordShareUsecase{repository: repository}
}

// Execute inserts the share read model described by input.
func (u *RecordShareUsecase) Execute(ctx context.Context, input RecordShareInput) error {
	if input.ShareID == uuid.Nil {
		return fmt.Errorf("record share usecase: share id is required")
	}
	if input.PostID == uuid.Nil {
		return fmt.Errorf("record share usecase: post id is required")
	}
	if input.UserID == uuid.Nil {
		return fmt.Errorf("record share usecase: user id is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("record share usecase: %w", err)
	}

	return nil
}
