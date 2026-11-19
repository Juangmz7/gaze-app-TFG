package usecase

import (
	"context"
	"fmt"
	"log/slog"
)

// myHistoryList names the history tab in logs.
const myHistoryList = "history"

// ListMyHistoryRepository reads what the history tab is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListMyHistoryRepository interface {
	RefListReader
	// FindUserViews reads the user's viewing history, one row per post, most recently
	// viewed first ((last_viewed_at, post_id) descending), keyset paged.
	FindUserViews(ctx context.Context, query UserGridQuery) ([]PostRef, error)
}

// ListMyHistoryUsecase serves the posts the caller viewed, one entry per post, most
// recently viewed first, keyset paged on the last view.
type ListMyHistoryUsecase struct {
	repository ListMyHistoryRepository
	logger     *slog.Logger
}

// NewListMyHistory creates a ListMyHistoryUsecase.
func NewListMyHistory(repository ListMyHistoryRepository, logger *slog.Logger) *ListMyHistoryUsecase {
	return &ListMyHistoryUsecase{repository: repository, logger: logger}
}

// Execute returns one page of the caller's history tab, each item with the time of the
// caller's last view. Posts gone or hidden by a block are left out (listOwnPosts).
//
// The cursor holds last_viewed_at, which a new view moves forward: a post viewed again
// while the caller pages moves to the top and is not served again further down, and
// the keyset never serves a row twice in one walk because rows only move up.
func (u *ListMyHistoryUsecase) Execute(ctx context.Context, input OwnListInput) (TimedGridPage, error) {
	page, err := listOwnPosts(ctx, u.repository, u.repository.FindUserViews, input, myHistoryList, u.logger)
	if err != nil {
		return TimedGridPage{}, fmt.Errorf("list my history usecase: %w", err)
	}

	return page, nil
}
