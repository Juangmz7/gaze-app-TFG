package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// CollabRequestStatusPending is the requests list's default status filter: the
// requests awaiting the owner.
const CollabRequestStatusPending = "PENDING"

// collabMemberStatuses are the statuses a collab_members row can hold, as
// post-command-service's CollabMemberStatus enum names them.
var collabMemberStatuses = map[string]struct{}{
	"PENDING":  {},
	"ACCEPTED": {},
	"REJECTED": {},
	"DELETED":  {},
	"LEFT":     {},
	"BANNED":   {},
}

// IsCollabMemberStatus reports whether status is a CollabMemberStatus, the values the
// requests list can be filtered by.
func IsCollabMemberStatus(status string) bool {
	_, found := collabMemberStatuses[status]
	return found
}

// ErrCollabNotFound is returned when the collab is not in the post_collabs read model
// (never opened, or deleted).
var ErrCollabNotFound = errors.New("collab not found")

// ErrNotCollabOwner is returned when the caller is not the collab's owner: only the
// owner may list its requests.
var ErrNotCollabOwner = errors.New("not the collab owner")

// collabRequestDropUserMissing is why a request whose requester is not in the users read
// model (deleted) is left out: there is no UserSummary to show.
const collabRequestDropUserMissing = "user_missing"

// ListCollabRequestsInput asks for one page of a collab's requests. UserID is the
// caller, from the authenticated context; CollabID comes from the path; Status is the
// filter (CollabRequestStatusPending by default, set by the handler). After is the
// decoded cursor, nil for the first page.
type ListCollabRequestsInput struct {
	UserID        uuid.UUID
	CollabID      uuid.UUID
	Status        string
	After         *CollabRequestsCursor
	Limit         int
	CorrelationID string
}

func (i ListCollabRequestsInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}
	if i.CollabID == uuid.Nil {
		return errors.New("collab id is required")
	}
	if !IsCollabMemberStatus(i.Status) {
		return fmt.Errorf("status %q is not a collab member status", i.Status)
	}

	return validLimit(i.Limit)
}

// CollabRequestsQuery reads, oldest first ((requested_at, user_id) ascending), up to
// Limit memberships of CollabID in Status strictly after After.
type CollabRequestsQuery struct {
	CollabID string
	Status   string
	After    *CollabRequestsCursor
	Limit    int
}

// ListCollabRequestsRepository reads what the requests list is built from. Implemented
// by feed/infrastructure/mongo.HydrationRepository.
type ListCollabRequestsRepository interface {
	// FindCollabOwner returns the collab's owner, and whether the collab exists.
	FindCollabOwner(ctx context.Context, collabID string) (string, bool, error)
	// FindCollabRequests reads one page of the collab's memberships in a status.
	FindCollabRequests(ctx context.Context, query CollabRequestsQuery) ([]CollabRequestRow, error)
	// FindCreators reads the named users, for the requesters' UserSummary.
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
}

// ListCollabRequestsUsecase serves a collab's join requests to its owner, oldest first,
// keyset paged.
type ListCollabRequestsUsecase struct {
	repository ListCollabRequestsRepository
	logger     *slog.Logger
}

// NewListCollabRequests creates a ListCollabRequestsUsecase.
func NewListCollabRequests(repository ListCollabRequestsRepository, logger *slog.Logger) *ListCollabRequestsUsecase {
	return &ListCollabRequestsUsecase{repository: repository, logger: logger}
}

// Execute returns one page, ErrCollabNotFound for an unknown collab, or
// ErrNotCollabOwner when the caller does not own it: the ownership is checked before
// any membership is read, so nobody else learns who asked. Three to four queries
// whatever the page size: the owner, one or two keyset ranges of limit+1 rows (an
// index seek after the cursor), and the requesters with one $in.
func (u *ListCollabRequestsUsecase) Execute(ctx context.Context, input ListCollabRequestsInput) (CollabRequestPage, error) {
	if err := input.validate(); err != nil {
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", err)
	}

	collabID := input.CollabID.String()
	owner, found, err := u.repository.FindCollabOwner(ctx, collabID)
	if err != nil {
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", err)
	}
	if !found {
		u.logger.InfoContext(ctx, "collab requests asked for an unknown collab", "correlation_id", input.CorrelationID, "userId", input.UserID, "collabId", collabID)
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", ErrCollabNotFound)
	}
	if owner != input.UserID.String() {
		u.logger.WarnContext(ctx, "collab requests asked by someone other than the owner", "correlation_id", input.CorrelationID, "userId", input.UserID, "collabId", collabID)
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", ErrNotCollabOwner)
	}

	rows, err := u.repository.FindCollabRequests(ctx, CollabRequestsQuery{CollabID: collabID, Status: input.Status, After: input.After, Limit: input.Limit + 1})
	if err != nil {
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", err)
	}
	var next *string
	if len(rows) > input.Limit {
		rows = rows[:input.Limit]
		last := rows[len(rows)-1]
		cursor := EncodeCollabRequestsCursor(CollabRequestsCursor{RequestedAt: last.RequestedAt, UserID: last.UserID})
		next = &cursor
	}

	items, err := u.hydrate(ctx, input, rows)
	if err != nil {
		return CollabRequestPage{}, fmt.Errorf("list collab requests usecase: %w", err)
	}

	return CollabRequestPage{Items: items, NextCursor: next}, nil
}

// hydrate attaches each requester's UserSummary with one FindCreators, in row order. A
// requester no longer projected (deleted) is left out and logged; the cursor still
// points after the last row read, so the next page is unaffected.
func (u *ListCollabRequestsUsecase) hydrate(ctx context.Context, input ListCollabRequestsInput, rows []CollabRequestRow) ([]CollabRequestItem, error) {
	if len(rows) == 0 {
		return []CollabRequestItem{}, nil
	}

	userIDs := make([]string, 0, len(rows))
	for _, row := range rows {
		userIDs = append(userIDs, row.UserID)
	}
	users, err := u.repository.FindCreators(ctx, userIDs)
	if err != nil {
		return nil, err
	}
	byID := make(map[string]FeedCreator, len(users))
	for _, user := range users {
		byID[user.UserID] = user
	}

	items := make([]CollabRequestItem, 0, len(rows))
	drops := gridDrops{}
	for _, row := range rows {
		user, found := byID[row.UserID]
		if !found {
			drops[collabRequestDropUserMissing]++
			continue
		}
		items = append(items, CollabRequestItem{User: user, Status: row.Status, RequestedAt: row.RequestedAt})
	}
	logDrops(ctx, u.logger, "collab requests left out of a page", drops,
		"correlation_id", input.CorrelationID, "userId", input.UserID, "collabId", input.CollabID)

	return items, nil
}
