package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// CloseCollabInput is the data required to apply a CollabClosedEvent to an
// existing collab read model document. It maps 1:1 to
// collab/infrastructure/mongo.Repository.Update's parameter (the mongo
// package imports this type directly instead of declaring its own local
// update shape, the same relationship RecordCollabOpenedInput has with
// Insert). ExpectedVersion is not set by the caller: CollabClosedEvent
// carries no version/sequence number of its own, so Execute looks up the
// document's current version via CloseCollabRepository.GetVersion before
// calling Update, and fills it in.
type CloseCollabInput struct {
	CollabID        uuid.UUID
	ExpectedVersion int64
	Title           string
	CreatedBy       uuid.UUID
	ClosedBy        uuid.UUID
	Status          string
	CollabCreatedAt time.Time
}

// CloseCollabRepository looks up the current version of a collab read
// model document and applies the optimistic-concurrency update described
// by CloseCollabInput. Implemented by collab/infrastructure/mongo.Repository.
type CloseCollabRepository interface {
	GetVersion(ctx context.Context, collabID uuid.UUID) (version int64, found bool, err error)
	Update(ctx context.Context, input CloseCollabInput) error
}

// CloseCollabUsecase projects a CollabClosedEvent into the collab read
// model.
//
// Concurrency strategy: CollabClosedEvent has no version of its own, so
// Execute reads the document's current version immediately before
// updating. If Update still reports a version conflict (another writer won
// the race between the read and the update), Execute does not retry
// in-process: it wraps and returns the error as a plain (non-permanent)
// error, so the RabbitMQ retry middleware redelivers the message and a
// later attempt re-reads the by-then-fresh version.
type CloseCollabUsecase struct {
	repository CloseCollabRepository
}

// NewCloseCollab creates a CloseCollabUsecase backed by repository.
func NewCloseCollab(repository CloseCollabRepository) *CloseCollabUsecase {
	return &CloseCollabUsecase{repository: repository}
}

// Execute applies the collab-closed update described by input.
func (u *CloseCollabUsecase) Execute(ctx context.Context, input CloseCollabInput) error {
	if input.CollabID == uuid.Nil {
		return fmt.Errorf("close collab usecase: collab id is required")
	}
	if input.CreatedBy == uuid.Nil {
		return fmt.Errorf("close collab usecase: created by is required")
	}
	if input.ClosedBy == uuid.Nil {
		return fmt.Errorf("close collab usecase: closed by is required")
	}
	if input.Status == "" {
		return fmt.Errorf("close collab usecase: status is required")
	}

	version, found, err := u.repository.GetVersion(ctx, input.CollabID)
	if err != nil {
		return fmt.Errorf("close collab usecase: get current version: %w", err)
	}
	if !found {
		return fmt.Errorf("close collab usecase: collab %s has no projection yet", input.CollabID)
	}
	input.ExpectedVersion = version

	if err := u.repository.Update(ctx, input); err != nil {
		return fmt.Errorf("close collab usecase: %w", err)
	}

	return nil
}
