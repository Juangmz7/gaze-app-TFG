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
//	createdAt           the post's created_at (also on the reposts grid)

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
		cover, err := signOptionalURL(ctx, signer, item.CoverThumbnailURL)
		if err != nil {
			return GridPageResponse{}, fmt.Errorf("map grid item %s: %w", item.PostID, err)
		}
		items = append(items, GridItemResponse{
			PostID:            item.PostID,
			CoverThumbnailURL: cover,
			MediaType:         item.MediaType,
			MediaCount:        item.MediaCount,
			PostType:          item.PostType,
			CollabStatus:      item.CollabStatus,
			LikeCount:         item.LikeCount,
			CreatedAt:         item.CreatedAt,
		})
	}

	return GridPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}
