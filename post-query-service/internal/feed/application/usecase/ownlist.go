package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// The own tabs (task 50): the posts the caller liked and the posts the caller viewed.
// They are private, so there is no target user: the list is always the caller's, whose
// id comes from the token and nowhere else.

// OwnListInput asks for one page of an own tab. UserID is the caller, from the
// authenticated context, and the only user whose list is read. After is the decoded
// cursor, nil for the first page.
type OwnListInput struct {
	UserID        uuid.UUID
	After         *GridCursor
	Limit         int
	CorrelationID string
}

// validate checks the domain rules every own tab input must meet.
func (i OwnListInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}

	return validLimit(i.Limit)
}

// listOwnPosts serves one page of the caller's ref list read by rows, most recent row
// first. A post is left out when it no longer exists, when its creator is not
// projected, or when its creator has a block with the caller (readRefPage). Queries:
// the caller's blocks, at most RefListMaxReads reads of three, and the page's collabs.
func listOwnPosts(ctx context.Context, reader RefListReader, rows refRows, input OwnListInput, list string, logger *slog.Logger) (TimedGridPage, error) {
	if err := input.validate(); err != nil {
		return TimedGridPage{}, err
	}

	userID := input.UserID.String()
	related, err := reader.FindAllBlockRelatedUserIDs(ctx, userID)
	if err != nil {
		return TimedGridPage{}, err
	}

	drops := gridDrops{}
	visible, next, err := readRefPage(ctx, reader, rows, userID, input.After, input.Limit, idSet(related), drops)
	if err != nil {
		return TimedGridPage{}, err
	}
	posts := make([]FeedPost, 0, len(visible))
	for _, row := range visible {
		posts = append(posts, row.post)
	}
	items, err := mapGridItems(ctx, reader, posts, drops, func(index int, item GridItem) TimedGridItem {
		return TimedGridItem{GridItem: item, At: visible[index].at}
	})
	if err != nil {
		return TimedGridPage{}, err
	}
	logDrops(ctx, logger, fmt.Sprintf("%s tab rows left out of a page", list), drops,
		"correlation_id", input.CorrelationID, "userId", input.UserID)

	return TimedGridPage{Items: items, NextCursor: next}, nil
}
