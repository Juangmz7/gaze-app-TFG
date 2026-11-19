package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// This file is the single place where the collab requests list's field names change
// (GET /api/content/collabs/{collabId}/requests, task 52):
//
//	API            MongoDB (collab_members)
//	user.userId    user_id (the rest of user from users, like any UserSummary)
//	status         status
//	requestedAt    requested_at

// CollabRequestDocument is the part of a collab_members document the requests list
// reads.
type CollabRequestDocument struct {
	UserID      string    `bson:"user_id"`
	Status      string    `bson:"status"`
	RequestedAt time.Time `bson:"requested_at"`
}

// CollabRequestRow is one membership of a requests page, before its user is hydrated.
type CollabRequestRow struct {
	UserID      string
	Status      string
	RequestedAt time.Time
}

// CollabRequestRowFromDocument maps a collab_members document.
func CollabRequestRowFromDocument(document CollabRequestDocument) CollabRequestRow {
	return CollabRequestRow{UserID: document.UserID, Status: document.Status, RequestedAt: document.RequestedAt}
}

// CollabRequestItem is one join request as the collab owner lists it.
type CollabRequestItem struct {
	User        FeedCreator
	Status      string
	RequestedAt time.Time
}

// CollabRequestPage is one page of a collab's requests, oldest first, and the cursor of
// the next page, nil on the last.
type CollabRequestPage struct {
	Items      []CollabRequestItem
	NextCursor *string
}

// CollabRequestItemResponse is the API shape of one join request: { user: UserSummary,
// status: CollabMemberStatus, requestedAt }.
type CollabRequestItemResponse struct {
	User        FeedCreatorResponse `json:"user"`
	Status      string              `json:"status"`
	RequestedAt time.Time           `json:"requestedAt"`
}

// CollabRequestPageResponse is the API shape of a requests page.
type CollabRequestPageResponse struct {
	Items      []CollabRequestItemResponse `json:"items"`
	NextCursor *string                     `json:"nextCursor"`
}

// CollabRequestPageToResponse maps a requests page to its API shape, with a list that
// is never null and each requester's picture signed by signer (userSummaryToResponse,
// as every UserSummary).
func CollabRequestPageToResponse(ctx context.Context, page CollabRequestPage, signer MediaURLSigner) (CollabRequestPageResponse, error) {
	if signer == nil {
		return CollabRequestPageResponse{}, errors.New("map collab requests page: a media url signer is required")
	}

	items := make([]CollabRequestItemResponse, 0, len(page.Items))
	for _, item := range page.Items {
		user, err := userSummaryToResponse(ctx, item.User, signer)
		if err != nil {
			return CollabRequestPageResponse{}, fmt.Errorf("map collab request of user %s: %w", item.User.UserID, err)
		}
		items = append(items, CollabRequestItemResponse{User: user, Status: item.Status, RequestedAt: item.RequestedAt})
	}

	return CollabRequestPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}
