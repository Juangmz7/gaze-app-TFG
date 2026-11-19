package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Collab member statuses, as post-command-service's CollabMemberStatus enum names them.
const (
	CollabMemberStatusPending  = "PENDING"
	CollabMemberStatusAccepted = "ACCEPTED"
	CollabMemberStatusRejected = "REJECTED"
	CollabMemberStatusDeleted  = "DELETED"
	CollabMemberStatusLeft     = "LEFT"
	CollabMemberStatusBanned   = "BANNED"
)

// Collab member roles, as post-command-service's CollabMemberRole enum names them.
const (
	CollabMemberRoleAdmin  = "ADMIN"
	CollabMemberRoleMember = "MEMBER"
)

// IsCollabMemberStatus reports whether status is one of the six member statuses.
func IsCollabMemberStatus(status string) bool {
	switch status {
	case CollabMemberStatusPending, CollabMemberStatusAccepted, CollabMemberStatusRejected,
		CollabMemberStatusDeleted, CollabMemberStatusLeft, CollabMemberStatusBanned:
		return true
	}

	return false
}

// IsCollabMemberRole reports whether role is one of the two member roles.
func IsCollabMemberRole(role string) bool {
	return role == CollabMemberRoleAdmin || role == CollabMemberRoleMember
}

// ApplyCollabMemberInput is the state of one (collab, user) membership after a join
// request or member event: rk.post.collab.request.created / accepted / declined /
// deleted and rk.post.collab.member.left / banned all carry the whole row.
//
// OccurredAt orders the events of one membership: none of them carries a version, so
// the event time is the only order post-command-service gives. RequestedAt is when the
// request was made (the member row's creation), the requests list's sort key; it is
// written only when the row is created here.
type ApplyCollabMemberInput struct {
	CollabID    uuid.UUID
	UserID      uuid.UUID
	Status      string
	Role        string
	RequestedAt time.Time
	OccurredAt  time.Time
}

// CollabMemberState is the stored part of a membership the usecase decides on.
type CollabMemberState struct {
	Status    string
	UpdatedAt time.Time
	Version   int64
}

// ApplyCollabMemberRepository keeps the collab_members projection. Every method runs
// inside the handler's transaction, so Insert must not be the duplicate-key path: the
// usecase looks the row up first. Implemented by
// collab/infrastructure/mongo.MemberRepository.
type ApplyCollabMemberRepository interface {
	// FindMember reads the stored membership, and whether there is one.
	FindMember(ctx context.Context, collabID, userID uuid.UUID) (CollabMemberState, bool, error)
	// InsertMember creates the membership at version 1, with updated_at = OccurredAt.
	InsertMember(ctx context.Context, input ApplyCollabMemberInput) error
	// UpdateMember sets status, role and updated_at = OccurredAt and bumps the version,
	// only while the stored version is still expectedVersion; it returns an error
	// otherwise, which is retryable.
	UpdateMember(ctx context.Context, input ApplyCollabMemberInput, expectedVersion int64) error
}

// ApplyCollabMemberResult says what Execute did with an event.
type ApplyCollabMemberResult string

// What Execute can do with an event.
const (
	// CollabMemberInserted means the membership was new and was created.
	CollabMemberInserted ApplyCollabMemberResult = "inserted"
	// CollabMemberUpdated means the event was newer than the stored row and moved it.
	CollabMemberUpdated ApplyCollabMemberResult = "updated"
	// CollabMemberStale means the stored row is as new as the event or newer, so the
	// event was ignored: a late created event never undoes an accept.
	CollabMemberStale ApplyCollabMemberResult = "stale"
)

// ApplyCollabMemberUsecase projects the join request and member events into
// collab_members, forward in time only.
type ApplyCollabMemberUsecase struct {
	repository ApplyCollabMemberRepository
}

// NewApplyCollabMember creates an ApplyCollabMemberUsecase backed by repository.
func NewApplyCollabMember(repository ApplyCollabMemberRepository) *ApplyCollabMemberUsecase {
	return &ApplyCollabMemberUsecase{repository: repository}
}

// Execute applies the event when it is newer than the stored row, compared at
// millisecond precision (MongoDB's, so a redelivered event compares equal to the time
// it stored). An event at the same or an earlier time than the stored one is stale and
// changes nothing: deliveries can arrive in any order (retries, the DLQ), and an
// accepted event processed before its created event must not be overwritten by it. A
// concurrent writer between the read and the write makes UpdateMember fail, and the
// redelivery decides again on the fresh row.
func (u *ApplyCollabMemberUsecase) Execute(ctx context.Context, input ApplyCollabMemberInput) (ApplyCollabMemberResult, error) {
	if err := input.validate(); err != nil {
		return "", fmt.Errorf("apply collab member usecase: %w", err)
	}
	input.OccurredAt = input.OccurredAt.UTC().Truncate(time.Millisecond)
	input.RequestedAt = input.RequestedAt.UTC().Truncate(time.Millisecond)

	stored, found, err := u.repository.FindMember(ctx, input.CollabID, input.UserID)
	if err != nil {
		return "", fmt.Errorf("apply collab member usecase: %w", err)
	}
	if !found {
		if err := u.repository.InsertMember(ctx, input); err != nil {
			return "", fmt.Errorf("apply collab member usecase: %w", err)
		}
		return CollabMemberInserted, nil
	}
	if !input.OccurredAt.After(stored.UpdatedAt) {
		return CollabMemberStale, nil
	}
	if err := u.repository.UpdateMember(ctx, input, stored.Version); err != nil {
		return "", fmt.Errorf("apply collab member usecase: %w", err)
	}

	return CollabMemberUpdated, nil
}

func (i ApplyCollabMemberInput) validate() error {
	if i.CollabID == uuid.Nil {
		return errors.New("collab id is required")
	}
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}
	if !IsCollabMemberStatus(i.Status) {
		return fmt.Errorf("status %q is not a collab member status", i.Status)
	}
	if !IsCollabMemberRole(i.Role) {
		return fmt.Errorf("role %q is not a collab member role", i.Role)
	}
	if i.RequestedAt.IsZero() {
		return errors.New("requested at is required")
	}
	if i.OccurredAt.IsZero() {
		return errors.New("occurred at is required")
	}

	return nil
}
