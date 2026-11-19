package rabbitmq

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

// What the six collab membership handlers share (rk.post.collab.request.created /
// accepted / declined / deleted, rk.post.collab.member.left / banned): their events
// differ in field names only, and each one carries the whole member row, so every
// handler decodes its own event into usecase.ApplyCollabMemberInput and hands it to the
// same transactional projection, the way postmediapayload.go shares the media payload
// between the post handlers.

// CollabMemberIdempotencyRepository records and checks processed events.
type CollabMemberIdempotencyRepository interface {
	IsProcessed(ctx context.Context, eventID uuid.UUID) (bool, error)
	MarkProcessed(ctx context.Context, eventID, correlationID uuid.UUID, eventType string) error
}

// CollabMemberTransactor runs its function inside one MongoDB transaction. Implemented
// by shared/infrastructure/database.Transactor.
type CollabMemberTransactor interface {
	WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error
}

// CollabMemberUsecase applies a membership event forward in time only. Implemented by
// usecase.ApplyCollabMemberUsecase.
type CollabMemberUsecase interface {
	Execute(ctx context.Context, input usecase.ApplyCollabMemberInput) (usecase.ApplyCollabMemberResult, error)
}

// collabMemberEnvelope is the part of a membership event every handler validates and
// logs.
type collabMemberEnvelope struct {
	ID            uuid.UUID
	CorrelationID uuid.UUID
	OccurredAt    time.Time
	CollabID      uuid.UUID
	UserID        uuid.UUID
	Status        string
	Role          string
}

// validateCollabMemberEnvelope checks the envelope, the ids, and that the status is the
// one the routing key promises (expectedStatus): post-command-service sets it from the
// member it just changed, so anything else is a broken contract.
func validateCollabMemberEnvelope(name string, envelope collabMemberEnvelope, statusField, expectedStatus string) error {
	if envelope.ID == uuid.Nil {
		return fmt.Errorf("%s event: id is required", name)
	}
	if envelope.CorrelationID == uuid.Nil {
		return fmt.Errorf("%s event: correlationId is required", name)
	}
	if envelope.OccurredAt.IsZero() {
		return fmt.Errorf("%s event: occurredAt is required", name)
	}
	if envelope.CollabID == uuid.Nil {
		return fmt.Errorf("%s event: collabId is required", name)
	}
	if envelope.UserID == uuid.Nil {
		return fmt.Errorf("%s event: userId is required", name)
	}
	if envelope.Status != expectedStatus {
		return fmt.Errorf("%s event: %s must be %s, got %q", name, statusField, expectedStatus, envelope.Status)
	}
	if !usecase.IsCollabMemberRole(envelope.Role) {
		return fmt.Errorf("%s event: role must be %s or %s, got %q", name, usecase.CollabMemberRoleAdmin, usecase.CollabMemberRoleMember, envelope.Role)
	}

	return nil
}

// collabMemberInput builds the usecase input. requestedAt is the member row's creation
// date when the event carries it; otherwise the event time stands in, which only
// matters when this event creates the row because the request's own event has not been
// projected yet.
func collabMemberInput(envelope collabMemberEnvelope, requestedAt *time.Time) usecase.ApplyCollabMemberInput {
	requested := envelope.OccurredAt
	if requestedAt != nil && !requestedAt.IsZero() {
		requested = *requestedAt
	}

	return usecase.ApplyCollabMemberInput{
		CollabID:    envelope.CollabID,
		UserID:      envelope.UserID,
		Status:      envelope.Status,
		Role:        envelope.Role,
		RequestedAt: requested,
		OccurredAt:  envelope.OccurredAt,
	}
}

// collabMemberProjector runs IsProcessed, the membership write and MarkProcessed in one
// transaction, and logs the outcome once it committed.
type collabMemberProjector struct {
	transactor  CollabMemberTransactor
	idempotency CollabMemberIdempotencyRepository
	usecase     CollabMemberUsecase
	logger      *slog.Logger
}

func (p collabMemberProjector) project(ctx context.Context, name, eventType string, envelope collabMemberEnvelope, input usecase.ApplyCollabMemberInput) error {
	if p.transactor == nil || p.idempotency == nil || p.usecase == nil || p.logger == nil {
		return errors.New(name + " handler: transactor, idempotency, usecase and logger are required")
	}

	// The driver may re-run this function on a transient transaction error, and a re-run
	// sees its own committed state, so the outcome is only read after the commit.
	duplicate := false
	var result usecase.ApplyCollabMemberResult
	err := p.transactor.WithTransaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := p.idempotency.IsProcessed(ctx, envelope.ID)
		if err != nil {
			return fmt.Errorf("check %s event %s processed: %w", name, envelope.ID, err)
		}
		duplicate = alreadyProcessed
		if alreadyProcessed {
			return nil
		}

		result, err = p.usecase.Execute(ctx, input)
		if err != nil {
			return fmt.Errorf("execute apply collab member usecase for event %s: %w", envelope.ID, err)
		}

		if err := p.idempotency.MarkProcessed(ctx, envelope.ID, envelope.CorrelationID, eventType); err != nil {
			return fmt.Errorf("mark %s event %s processed: %w", name, envelope.ID, err)
		}

		return nil
	})
	if err != nil {
		return fmt.Errorf("project %s event %s: %w", name, envelope.ID, err)
	}

	attributes := []any{"event_id", envelope.ID, "correlation_id", envelope.CorrelationID, "userId", envelope.UserID, "collab_id", envelope.CollabID}
	if duplicate {
		p.logger.WarnContext(ctx, "discarding duplicate "+name+" event", attributes...)
		return nil
	}
	if result == usecase.CollabMemberStale {
		p.logger.InfoContext(ctx, "ignoring stale "+name+" event: the membership already holds a newer state", append(attributes, "status", envelope.Status)...)
		return nil
	}
	p.logger.InfoContext(ctx, name+" event projected", append(attributes, "status", envelope.Status, "result", string(result))...)

	return nil
}
