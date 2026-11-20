package usecase

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// UpdateCollabJoinInstructionsInput is the data required to apply a
// CollabJoinInstructionsUpdatedEvent to an existing collab read model document.
// JoinInstructions is empty when the ADMIN cleared them. ExpectedVersion is not set
// by the caller: Execute fills it in from
// UpdateCollabJoinInstructionsRepository.GetJoinInstructionsState, the same way
// CloseCollabUsecase does, because the event carries no version of its own.
type UpdateCollabJoinInstructionsInput struct {
	CollabID         uuid.UUID
	JoinInstructions string
	OccurredAt       time.Time
	ExpectedVersion  int64
}

// CollabJoinInstructionsState is what Execute needs to know about the stored collab
// before writing: its version, for the optimistic-concurrency write, and when its
// instructions last changed, to apply updates only forward in time.
type CollabJoinInstructionsState struct {
	Version   int64
	UpdatedAt time.Time
}

// UpdateCollabJoinInstructionsRepository reads the stored instructions' state and
// applies the version-gated write. Implemented by
// collab/infrastructure/mongo.Repository.
type UpdateCollabJoinInstructionsRepository interface {
	GetJoinInstructionsState(ctx context.Context, collabID uuid.UUID) (state CollabJoinInstructionsState, found bool, err error)
	UpdateJoinInstructions(ctx context.Context, input UpdateCollabJoinInstructionsInput) error
}

// UpdateCollabJoinInstructionsUsecase projects a CollabJoinInstructionsUpdatedEvent
// into the collab read model (task 61).
//
// Ordering: the event carries no version, so updates are ordered by occurredAt,
// truncated to the millisecond MongoDB stores (a redelivery then compares equal). An
// event newer than the stored instructions' update time is written with a
// version-gated update; one at the same time or older is stale and writes nothing. A
// version conflict or a collab that is not projected yet is returned as a plain,
// retryable error, so the retry middleware redelivers the message.
type UpdateCollabJoinInstructionsUsecase struct {
	repository UpdateCollabJoinInstructionsRepository
}

// NewUpdateCollabJoinInstructions creates an UpdateCollabJoinInstructionsUsecase
// backed by repository.
func NewUpdateCollabJoinInstructions(repository UpdateCollabJoinInstructionsRepository) *UpdateCollabJoinInstructionsUsecase {
	return &UpdateCollabJoinInstructionsUsecase{repository: repository}
}

// Execute applies input when it is newer than the stored instructions, and reports
// whether it wrote anything: false means the event was stale.
func (u *UpdateCollabJoinInstructionsUsecase) Execute(ctx context.Context, input UpdateCollabJoinInstructionsInput) (bool, error) {
	if input.CollabID == uuid.Nil {
		return false, fmt.Errorf("update collab join instructions usecase: collab id is required")
	}
	if input.OccurredAt.IsZero() {
		return false, fmt.Errorf("update collab join instructions usecase: occurred at is required")
	}
	input.JoinInstructions = strings.TrimSpace(input.JoinInstructions)
	input.OccurredAt = input.OccurredAt.UTC().Truncate(time.Millisecond)

	state, found, err := u.repository.GetJoinInstructionsState(ctx, input.CollabID)
	if err != nil {
		return false, fmt.Errorf("update collab join instructions usecase: get current state: %w", err)
	}
	if !found {
		return false, fmt.Errorf("update collab join instructions usecase: collab %s has no projection yet", input.CollabID)
	}
	if !input.OccurredAt.After(state.UpdatedAt) {
		return false, nil
	}
	input.ExpectedVersion = state.Version

	if err := u.repository.UpdateJoinInstructions(ctx, input); err != nil {
		return false, fmt.Errorf("update collab join instructions usecase: %w", err)
	}

	return true, nil
}
