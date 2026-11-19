package usecase

import (
	"context"
	"fmt"
	"log/slog"
)

// myLikedList names the liked tab in logs.
const myLikedList = "liked"

// ListMyLikedRepository reads what the liked tab is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListMyLikedRepository interface {
	RefListReader
	// FindUserLikes reads the user's likes, most recent first ((created_at, post_id)
	// descending), keyset paged.
	FindUserLikes(ctx context.Context, query UserGridQuery) ([]PostRef, error)
}

// ListMyLikedUsecase serves the posts the caller liked, most recently liked first,
// keyset paged on the like.
type ListMyLikedUsecase struct {
	repository ListMyLikedRepository
	logger     *slog.Logger
}

// NewListMyLiked creates a ListMyLikedUsecase.
func NewListMyLiked(repository ListMyLikedRepository, logger *slog.Logger) *ListMyLikedUsecase {
	return &ListMyLikedUsecase{repository: repository, logger: logger}
}

// Execute returns one page of the caller's liked tab, each item with the time of the
// like. Posts gone or hidden by a block are left out (listOwnPosts).
func (u *ListMyLikedUsecase) Execute(ctx context.Context, input OwnListInput) (TimedGridPage, error) {
	page, err := listOwnPosts(ctx, u.repository, u.repository.FindUserLikes, input, myLikedList, u.logger)
	if err != nil {
		return TimedGridPage{}, fmt.Errorf("list my liked usecase: %w", err)
	}

	return page, nil
}
