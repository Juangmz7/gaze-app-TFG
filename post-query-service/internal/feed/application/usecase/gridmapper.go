package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// This file is the single place where the profile grid shape (task 49) is built. A
// GridItem is a summary of a post, derived from the same card as a feed item or a post
// view (FeedCardFromPost), so its cover, post type and collab follow the same rules:
//
//	API                 Source
//	coverThumbnailUrl   FeedCoverThumbnailURL (first media by order), signed
//	mediaType           media_type of the first media by order
//	mediaCount          number of media elements
//	postType            COLLAB when a collab resolves (feedState.collabFor), else post_type
//	collabStatus        the resolved collab's status, null without one
//	likeCount           stats.like_count
//	createdAt           the post's created_at (also on the reposts grid and the own tabs)
//
// The own tabs (task 50) add the time of the caller's row to the same item:
//
//	likedAt             post_likes.created_at (GET /api/content/me/liked)
//	viewedAt            view_history.last_viewed_at (GET /api/content/me/history)

// GridItem is one post of a profile grid.
type GridItem struct {
	PostID            string
	CoverThumbnailURL *string
	MediaType         string
	MediaCount        int
	PostType          string
	CollabStatus      *string
	LikeCount         int64
	CreatedAt         time.Time
}

// GridPage is one page of a profile grid. NextCursor is the opaque cursor of the next
// page, null on the last one.
type GridPage struct {
	Items      []GridItem
	NextCursor *string
}

// GridItemResponse is the API shape of one grid item.
type GridItemResponse struct {
	PostID            string    `json:"postId"`
	CoverThumbnailURL *string   `json:"coverThumbnailUrl"`
	MediaType         string    `json:"mediaType"`
	MediaCount        int       `json:"mediaCount"`
	PostType          string    `json:"postType"`
	CollabStatus      *string   `json:"collabStatus"`
	LikeCount         int64     `json:"likeCount"`
	CreatedAt         time.Time `json:"createdAt"`
}

// GridPageResponse is the API shape of a grid page.
type GridPageResponse struct {
	Items      []GridItemResponse `json:"items"`
	NextCursor *string            `json:"nextCursor"`
}

// TimedGridItem is a grid item of a list ordered by the caller's own row: At is when the
// caller liked the post (liked tab) or last viewed it (history tab).
type TimedGridItem struct {
	GridItem
	At time.Time
}

// TimedGridPage is one page of an own tab. NextCursor is null on the last page.
type TimedGridPage struct {
	Items      []TimedGridItem
	NextCursor *string
}

// LikedItemResponse is the API shape of one liked tab item: GridItem & { likedAt }.
type LikedItemResponse struct {
	GridItemResponse
	LikedAt time.Time `json:"likedAt"`
}

// LikedPageResponse is the API shape of a liked tab page.
type LikedPageResponse struct {
	Items      []LikedItemResponse `json:"items"`
	NextCursor *string             `json:"nextCursor"`
}

// HistoryItemResponse is the API shape of one history tab item: GridItem & { viewedAt }.
type HistoryItemResponse struct {
	GridItemResponse
	ViewedAt time.Time `json:"viewedAt"`
}

// HistoryPageResponse is the API shape of a history tab page.
type HistoryPageResponse struct {
	Items      []HistoryItemResponse `json:"items"`
	NextCursor *string               `json:"nextCursor"`
}

// GridItemFromPost summarizes a post and the collab it resolves to (nil for none). It
// reports false for a post with no media, which has no media type to show: never
// published by post-command-service, whose posts are projected once their media is
// verified, so the caller leaves it out with a warning.
func GridItemFromPost(post FeedPost, collab *FeedCollab) (GridItem, bool) {
	card := FeedCardFromPost(post, FeedCreator{}, collab, FeedViewerState{})
	if len(card.Media) == 0 {
		return GridItem{}, false
	}

	// FeedPostFromDocument sorts the media by order, so the first is the cover.
	return GridItem{
		PostID:            card.PostID,
		CoverThumbnailURL: card.CoverThumbnailURL,
		MediaType:         card.Media[0].MediaType,
		MediaCount:        len(card.Media),
		PostType:          card.PostType,
		CollabStatus:      card.CollabStatus,
		LikeCount:         card.Stats.LikeCount,
		CreatedAt:         card.CreatedAt,
	}, true
}

// GridPageToResponse maps a page to its API shape, with a list that is never null and
// every cover signed by signer.
func GridPageToResponse(ctx context.Context, page GridPage, signer MediaURLSigner) (GridPageResponse, error) {
	if signer == nil {
		return GridPageResponse{}, errors.New("map grid page: a media url signer is required")
	}

	items := make([]GridItemResponse, 0, len(page.Items))
	for _, item := range page.Items {
		response, err := gridItemToResponse(ctx, item, signer)
		if err != nil {
			return GridPageResponse{}, err
		}
		items = append(items, response)
	}

	return GridPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}

// LikedPageToResponse maps a liked tab page to its API shape, At as likedAt.
func LikedPageToResponse(ctx context.Context, page TimedGridPage, signer MediaURLSigner) (LikedPageResponse, error) {
	items, err := timedItemsToResponse(ctx, page, signer, func(item GridItemResponse, at time.Time) LikedItemResponse {
		return LikedItemResponse{GridItemResponse: item, LikedAt: at}
	})
	if err != nil {
		return LikedPageResponse{}, fmt.Errorf("map liked page: %w", err)
	}

	return LikedPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}

// HistoryPageToResponse maps a history tab page to its API shape, At as viewedAt.
func HistoryPageToResponse(ctx context.Context, page TimedGridPage, signer MediaURLSigner) (HistoryPageResponse, error) {
	items, err := timedItemsToResponse(ctx, page, signer, func(item GridItemResponse, at time.Time) HistoryItemResponse {
		return HistoryItemResponse{GridItemResponse: item, ViewedAt: at}
	})
	if err != nil {
		return HistoryPageResponse{}, fmt.Errorf("map history page: %w", err)
	}

	return HistoryPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}

// timedItemsToResponse maps the items of an own tab page, never null, each cover signed
// by signer, through build.
func timedItemsToResponse[T any](ctx context.Context, page TimedGridPage, signer MediaURLSigner, build func(GridItemResponse, time.Time) T) ([]T, error) {
	if signer == nil {
		return nil, errors.New("a media url signer is required")
	}

	items := make([]T, 0, len(page.Items))
	for _, item := range page.Items {
		response, err := gridItemToResponse(ctx, item.GridItem, signer)
		if err != nil {
			return nil, err
		}
		items = append(items, build(response, item.At))
	}

	return items, nil
}

// gridItemToResponse maps one grid item, signing its cover.
func gridItemToResponse(ctx context.Context, item GridItem, signer MediaURLSigner) (GridItemResponse, error) {
	cover, err := signOptionalURL(ctx, signer, item.CoverThumbnailURL)
	if err != nil {
		return GridItemResponse{}, fmt.Errorf("map grid item %s: %w", item.PostID, err)
	}

	return GridItemResponse{
		PostID:            item.PostID,
		CoverThumbnailURL: cover,
		MediaType:         item.MediaType,
		MediaCount:        item.MediaCount,
		PostType:          item.PostType,
		CollabStatus:      item.CollabStatus,
		LikeCount:         item.LikeCount,
		CreatedAt:         item.CreatedAt,
	}, nil
}
