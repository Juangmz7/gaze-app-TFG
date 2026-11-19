package usecase

import (
	"context"
	"fmt"
	"log/slog"
)

// userRepostsGrid names the reposts grid in logs.
const userRepostsGrid = "reposts"

// ListUserRepostsRepository reads what the reposts grid is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListUserRepostsRepository interface {
	RefListReader
	FindUserShares(ctx context.Context, query UserGridQuery) ([]PostRef, error)
}

// ListUserRepostsUsecase serves the posts a user shared, most recently shared first,
// keyset paged on the share.
type ListUserRepostsUsecase struct {
	repository ListUserRepostsRepository
	logger     *slog.Logger
}

// NewListUserReposts creates a ListUserRepostsUsecase.
func NewListUserReposts(repository ListUserRepostsRepository, logger *slog.Logger) *ListUserRepostsUsecase {
	return &ListUserRepostsUsecase{repository: repository, logger: logger}
}

// Execute returns one page, or ErrGridUserNotFound when the caller may not see the
// user. A shared post is left out when it no longer exists, when its creator is not
// projected, or when its creator has a block with the caller: the same posts GetPost
// answers 404 for (readRefPage, at most RefListMaxReads reads of three queries each),
// plus the user, the caller's blocks and the page's collabs.
func (u *ListUserRepostsUsecase) Execute(ctx context.Context, input UserGridInput) (GridPage, error) {
	if err := input.validate(); err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}

	targetID := input.TargetUserID.String()
	blocked, reason, err := findVisibleTarget(ctx, u.repository, input.UserID.String(), targetID)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}
	if reason != "" {
		logGridNotFound(ctx, u.logger, userRepostsGrid, input, reason)
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", ErrGridUserNotFound)
	}

	drops := gridDrops{}
	shared, next, err := readRefPage(ctx, u.repository, u.repository.FindUserShares, targetID, input.After, input.Limit, blocked, drops)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}
	posts := make([]FeedPost, 0, len(shared))
	for _, share := range shared {
		posts = append(posts, share.post)
	}

	items, err := gridItems(ctx, u.repository, posts, drops)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}
	logGridDrops(ctx, u.logger, userRepostsGrid, input, drops)

	return GridPage{Items: items, NextCursor: next}, nil
}
