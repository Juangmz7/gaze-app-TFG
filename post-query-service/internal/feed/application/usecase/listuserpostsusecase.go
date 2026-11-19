package usecase

import (
	"context"
	"fmt"
	"log/slog"
)

// userPostsGrid names the posts grid in logs.
const userPostsGrid = "posts"

// ListUserPostsRepository reads what the posts grid is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListUserPostsRepository interface {
	gridReader
	FindUserPosts(ctx context.Context, query UserGridQuery) ([]FeedPost, error)
}

// ListUserPostsUsecase serves the posts of a user, newest first, keyset paged.
type ListUserPostsUsecase struct {
	repository ListUserPostsRepository
	logger     *slog.Logger
}

// NewListUserPosts creates a ListUserPostsUsecase.
func NewListUserPosts(repository ListUserPostsRepository, logger *slog.Logger) *ListUserPostsUsecase {
	return &ListUserPostsUsecase{repository: repository, logger: logger}
}

// Execute returns one page, or ErrGridUserNotFound when the caller may not see the
// user. Four queries whatever the page size: the user, the caller's blocks, the page
// (limit+1 posts, an index seek after the cursor) and the page's collabs.
func (u *ListUserPostsUsecase) Execute(ctx context.Context, input UserGridInput) (GridPage, error) {
	if err := input.validate(); err != nil {
		return GridPage{}, fmt.Errorf("list user posts usecase: %w", err)
	}

	targetID := input.TargetUserID.String()
	_, reason, err := findVisibleTarget(ctx, u.repository, input.UserID.String(), targetID)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user posts usecase: %w", err)
	}
	if reason != "" {
		logGridNotFound(ctx, u.logger, userPostsGrid, input, reason)
		return GridPage{}, fmt.Errorf("list user posts usecase: %w", ErrGridUserNotFound)
	}

	rows, err := u.repository.FindUserPosts(ctx, UserGridQuery{UserID: targetID, After: input.After, Limit: input.Limit + 1})
	if err != nil {
		return GridPage{}, fmt.Errorf("list user posts usecase: %w", err)
	}
	var next *string
	if len(rows) > input.Limit {
		rows = rows[:input.Limit]
		last := rows[len(rows)-1]
		next = encodeGridCursorAt(last.CreatedAt, last.PostID)
	}

	drops := gridDrops{}
	items, err := gridItems(ctx, u.repository, rows, drops)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user posts usecase: %w", err)
	}
	logGridDrops(ctx, u.logger, userPostsGrid, input, drops)

	return GridPage{Items: items, NextCursor: next}, nil
}
