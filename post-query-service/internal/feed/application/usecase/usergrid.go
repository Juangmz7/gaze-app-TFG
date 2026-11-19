package usecase

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// ErrGridUserNotFound is returned when the user whose grid is asked for cannot be shown
// to the caller: not in the users read model (unknown or deleted), or in a block
// relation with the caller in either direction. One error for all of them, so the
// answer never reveals a block.
var ErrGridUserNotFound = errors.New("user not found")

// Not-found and drop reasons of the profile grids, logged and never answered.
const (
	gridReasonUserMissing        = "user_missing"
	gridReasonUserBlocked        = "blocked"
	gridDropReasonNoMedia        = "no_media"
	gridDropReasonPostMissing    = "post_missing"
	gridDropReasonCreatorMissing = "creator_missing"
	gridDropReasonCreatorBlocked = "creator_blocked"
)

// UserGridInput asks for one page of a user's profile grid (posts or reposts). UserID is
// the caller, from the authenticated context; TargetUserID is the profile's owner, from
// the path. After is the decoded cursor, nil for the first page.
type UserGridInput struct {
	UserID        uuid.UUID
	TargetUserID  uuid.UUID
	After         *GridCursor
	Limit         int
	CorrelationID string
}

// validate checks the domain rules every grid input must meet.
func (i UserGridInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}
	if i.TargetUserID == uuid.Nil {
		return errors.New("target user id is required")
	}

	return validLimit(i.Limit)
}

// UserGridQuery reads, newest first ((created_at, post_id) descending), up to Limit rows
// of UserID strictly after After.
type UserGridQuery struct {
	UserID string
	After  *GridCursor
	Limit  int
}

// gridReader is what both grids read besides their rows: one projected query per
// method. Implemented by feed/infrastructure/mongo.HydrationRepository.
type gridReader interface {
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
	FindAllBlockRelatedUserIDs(ctx context.Context, viewerID string) ([]string, error)
	FindCollabs(ctx context.Context, collabIDs, postIDs []string) ([]FeedCollab, error)
}

// findVisibleTarget reads the target user and every block of the caller (two queries).
// It returns a not-found reason, and no error, when the target is not projected or has
// a block with the caller. The block set is returned for the reposts grid, which also
// hides posts whose creator has a block with the caller.
func findVisibleTarget(ctx context.Context, reader gridReader, viewerID, targetID string) (map[string]struct{}, string, error) {
	users, err := reader.FindCreators(ctx, []string{targetID})
	if err != nil {
		return nil, "", err
	}
	if !containsUser(users, targetID) {
		return nil, gridReasonUserMissing, nil
	}

	related, err := reader.FindAllBlockRelatedUserIDs(ctx, viewerID)
	if err != nil {
		return nil, "", err
	}
	blocked := idSet(related)
	if _, isBlocked := blocked[targetID]; isBlocked {
		return nil, gridReasonUserBlocked, nil
	}

	return blocked, "", nil
}

func containsUser(users []FeedCreator, userID string) bool {
	for _, user := range users {
		if user.UserID == userID {
			return true
		}
	}

	return false
}

// gridDrops counts the rows a page left out, by reason.
type gridDrops map[string]int

// gridItems summarizes posts in order, resolving their collabs with one FindCollabs, so
// a page never costs a query per post. A post without media is counted in drops.
func gridItems(ctx context.Context, reader gridReader, posts []FeedPost, drops gridDrops) ([]GridItem, error) {
	if len(posts) == 0 {
		return []GridItem{}, nil
	}

	postIDs := make([]string, 0, len(posts))
	var collabIDs []string
	for _, post := range posts {
		postIDs = append(postIDs, post.PostID)
		if post.CollabID != nil {
			collabIDs = append(collabIDs, *post.CollabID)
		}
	}
	collabs, err := reader.FindCollabs(ctx, collabIDs, postIDs)
	if err != nil {
		return nil, err
	}
	index := indexCollabs(collabs)

	items := make([]GridItem, 0, len(posts))
	for _, post := range posts {
		item, ok := GridItemFromPost(post, index.collabFor(post))
		if !ok {
			drops[gridDropReasonNoMedia]++
			continue
		}
		items = append(items, item)
	}

	return items, nil
}

// encodeGridCursorAt is the cursor that resumes a grid after the row with this key.
func encodeGridCursorAt(createdAt time.Time, postID string) *string {
	cursor := EncodeGridCursor(GridCursor{CreatedAt: createdAt, PostID: postID})
	return &cursor
}

// logGridNotFound logs why a grid is not shown, which the answer never says.
func logGridNotFound(ctx context.Context, logger *slog.Logger, grid string, input UserGridInput, reason string) {
	logger.InfoContext(ctx, grid+" grid not shown to the caller", "correlation_id", input.CorrelationID, "userId", input.UserID,
		"targetUserId", input.TargetUserID, "reason", reason)
}

// logGridDrops logs the rows a page left out. A post without media is an anomaly of
// the read model, so it is a warning; the other reasons (a reposted post deleted since,
// or hidden by a block) are expected.
func logGridDrops(ctx context.Context, logger *slog.Logger, grid string, input UserGridInput, drops gridDrops) {
	for reason, count := range drops {
		attributes := []any{"correlation_id", input.CorrelationID, "userId", input.UserID, "targetUserId", input.TargetUserID,
			"reason", reason, "count", count}
		if reason == gridDropReasonNoMedia {
			logger.WarnContext(ctx, grid+" grid rows left out of a page", attributes...)
			continue
		}
		logger.InfoContext(ctx, grid+" grid rows left out of a page", attributes...)
	}
}
