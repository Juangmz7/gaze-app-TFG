package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// ErrCommentVersionConflict is the sentinel UpdateCommentRepository.Update
// wraps when the stored comment document's version does not match
// UpdateCommentInput.ExpectedVersion. It is declared here, in the
// application layer, rather than by the mongo package (contrast with
// post/collab/user, whose equivalent sentinel lives in their mongo
// package) because UpdateCommentUsecase.Execute itself needs to detect
// this specific failure to apply the compensating safety net described on
// UpdateCommentUsecase; the mongo package already imports this usecase
// package (for Insert's parameter type) so it can wrap this sentinel
// without an import cycle.
var ErrCommentVersionConflict = errors.New("comment version conflict")

// UpdateCommentInput is the data required to apply a CommentUpdatedEvent to
// an existing comment read model document. ExpectedVersion is not set by
// the caller: CommentUpdatedEvent carries no version/sequence number of its
// own, so Execute looks up the document's current version via
// UpdateCommentRepository.GetVersion before calling Update, and fills it
// in.
type UpdateCommentInput struct {
	CommentID       uuid.UUID
	ExpectedVersion int64
	PostID          uuid.UUID
	UserID          uuid.UUID
	Content         string
	UpdatedAt       time.Time
}

// UpdateCommentRepository looks up the current version of a comment read
// model document, applies the optimistic-concurrency update described by
// UpdateCommentInput, and can report the currently stored content/updatedAt
// for a document (used by Execute's version-conflict safety net).
// Implemented by comment/infrastructure/mongo.Repository.
type UpdateCommentRepository interface {
	GetVersion(ctx context.Context, commentID uuid.UUID) (version int64, found bool, err error)
	Update(ctx context.Context, input UpdateCommentInput) error
	FindContentAndUpdatedAt(ctx context.Context, commentID uuid.UUID) (content string, updatedAt time.Time, found bool, err error)
}

// UpdateCommentUsecase projects a CommentUpdatedEvent into the comment read
// model.
//
// Compensating rule for a missing idempotency gate: unlike every other
// event this service consumes, the real Java CommentUpdatedEvent does not
// implement EventMessage and therefore carries no id/correlationId/
// occurredAt envelope at all (verified against
// post-command-service/.../comment/infrastructure/events/CommentUpdatedEvent.java).
// CommentUpdatedHandler cannot call IsProcessed/MarkProcessed by event UUID
// because there is none to fabricate, so this usecase is the only one in
// the service that runs without an upstream idempotency check. A future
// task should fix post-command-service so CommentUpdatedEvent implements
// EventMessage like every other domain event; until then, correctness
// relies on two things: (1) the version-gated Update below, and (2) this
// extra safety net specific to this usecase: if Update reports
// ErrCommentVersionConflict, Execute re-fetches the comment's currently
// stored content/updatedAt. If they already equal the incoming event's
// Content/UpdatedAt, the "conflict" is actually a harmless redelivery of an
// update that already landed (the version moved on because this same event
// applied once already), so Execute logs it and returns nil (ack) instead
// of propagating the error for retry. If the stored fields do not match,
// it is a genuine conflict (a newer update raced ahead), so Execute returns
// the error as usual for redelivery.
//
// Concurrency strategy otherwise matches CloseCollabUsecase/
// UpdateUserUsecase: Execute does not retry in-process on a genuine
// conflict, it returns a plain (non-permanent) error so the RabbitMQ retry
// middleware redelivers the message.
type UpdateCommentUsecase struct {
	repository UpdateCommentRepository
	logger     *slog.Logger
}

// NewUpdateComment creates an UpdateCommentUsecase backed by repository,
// using logger to record the version-conflict safety net's outcome (see
// UpdateCommentUsecase's doc comment).
func NewUpdateComment(repository UpdateCommentRepository, logger *slog.Logger) *UpdateCommentUsecase {
	return &UpdateCommentUsecase{repository: repository, logger: logger}
}

// Execute applies the comment-updated projection described by input.
func (u *UpdateCommentUsecase) Execute(ctx context.Context, input UpdateCommentInput) error {
	if input.CommentID == uuid.Nil {
		return fmt.Errorf("update comment usecase: comment id is required")
	}

	version, found, err := u.repository.GetVersion(ctx, input.CommentID)
	if err != nil {
		return fmt.Errorf("update comment usecase: get current version: %w", err)
	}
	if !found {
		return fmt.Errorf("update comment usecase: comment %s has no projection yet", input.CommentID)
	}
	input.ExpectedVersion = version

	updateErr := u.repository.Update(ctx, input)
	if updateErr == nil {
		return nil
	}
	if !errors.Is(updateErr, ErrCommentVersionConflict) {
		return fmt.Errorf("update comment usecase: %w", updateErr)
	}

	content, updatedAt, storedFound, findErr := u.repository.FindContentAndUpdatedAt(ctx, input.CommentID)
	if findErr != nil {
		return fmt.Errorf("update comment usecase: re-check after version conflict: %w", findErr)
	}
	if storedFound && content == input.Content && updatedAt.Equal(input.UpdatedAt) {
		if u.logger != nil {
			u.logger.InfoContext(ctx, "comment update version conflict was a harmless redelivery of an already-applied update",
				"comment_id", input.CommentID)
		}
		return nil
	}

	return fmt.Errorf("update comment usecase: %w", updateErr)
}
