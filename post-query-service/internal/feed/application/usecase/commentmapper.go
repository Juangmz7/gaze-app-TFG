package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// This file is the single place where comment field names change between MongoDB
// and the API of the comment lists (task 48), as feedmapper.go is for posts:
//
//	API          post_comments   Note
//	author       user_id         the author's UserSummary, from users
//	replyTo      reply_to        stored as "" for a top-level comment, null in the API
//	likeCount    like_count
//	replyCount   reply_count
//	likedByMe    -               from comment_likes, for the caller
//
// The author is a UserSummary, the same shape and the same signing as a feed card's
// creator: both go through userSummaryToResponse in feedmapper.go.

// ContentComment is a comment as the comment lists read it from post_comments.
type ContentComment struct {
	CommentID  string
	PostID     string
	AuthorID   string
	Content    string
	ReplyTo    *string
	LikeCount  int64
	ReplyCount int64
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

// CommentItem is one comment of a page as the caller sees it.
type CommentItem struct {
	Comment   ContentComment
	Author    FeedCreator
	LikedByMe bool
}

// CommentPage is one page of a comment list. NextCursor is the opaque cursor of the
// next page, null on the last one.
type CommentPage struct {
	Items      []CommentItem
	NextCursor *string
}

// CommentDocument is the part of a post_comments document the comment lists read.
type CommentDocument struct {
	CommentID  string    `bson:"comment_id"`
	PostID     string    `bson:"post_id"`
	UserID     string    `bson:"user_id"`
	Content    string    `bson:"content"`
	ReplyTo    string    `bson:"reply_to"`
	LikeCount  int64     `bson:"like_count"`
	ReplyCount int64     `bson:"reply_count"`
	CreatedAt  time.Time `bson:"created_at"`
	UpdatedAt  time.Time `bson:"updated_at"`
}

// CommentResponse is the API shape of one comment.
type CommentResponse struct {
	CommentID  string              `json:"commentId"`
	PostID     string              `json:"postId"`
	Author     FeedCreatorResponse `json:"author"`
	Content    string              `json:"content"`
	ReplyTo    *string             `json:"replyTo"`
	LikeCount  int64               `json:"likeCount"`
	ReplyCount int64               `json:"replyCount"`
	LikedByMe  bool                `json:"likedByMe"`
	CreatedAt  time.Time           `json:"createdAt"`
	UpdatedAt  time.Time           `json:"updatedAt"`
}

// CommentPageResponse is the API shape of a comment list page.
type CommentPageResponse struct {
	Items      []CommentResponse `json:"items"`
	NextCursor *string           `json:"nextCursor"`
}

// CommentFromDocument maps a post_comments document, turning the stored empty
// reply_to of a top-level comment into null.
func CommentFromDocument(document CommentDocument) ContentComment {
	return ContentComment{
		CommentID:  document.CommentID,
		PostID:     document.PostID,
		AuthorID:   document.UserID,
		Content:    document.Content,
		ReplyTo:    nullIfEmpty(document.ReplyTo),
		LikeCount:  document.LikeCount,
		ReplyCount: document.ReplyCount,
		CreatedAt:  document.CreatedAt,
		UpdatedAt:  document.UpdatedAt,
	}
}

// CommentPageToResponse maps a page to its API shape, with a list that is never null
// and every author picture signed by signer.
func CommentPageToResponse(ctx context.Context, page CommentPage, signer MediaURLSigner) (CommentPageResponse, error) {
	if signer == nil {
		return CommentPageResponse{}, errors.New("map comment page: a media url signer is required")
	}

	items := make([]CommentResponse, 0, len(page.Items))
	for _, item := range page.Items {
		author, err := userSummaryToResponse(ctx, item.Author, signer)
		if err != nil {
			return CommentPageResponse{}, fmt.Errorf("map comment %s: %w", item.Comment.CommentID, err)
		}
		items = append(items, CommentResponse{
			CommentID:  item.Comment.CommentID,
			PostID:     item.Comment.PostID,
			Author:     author,
			Content:    item.Comment.Content,
			ReplyTo:    item.Comment.ReplyTo,
			LikeCount:  item.Comment.LikeCount,
			ReplyCount: item.Comment.ReplyCount,
			LikedByMe:  item.LikedByMe,
			CreatedAt:  item.Comment.CreatedAt,
			UpdatedAt:  item.Comment.UpdatedAt,
		})
	}

	return CommentPageResponse{Items: items, NextCursor: page.NextCursor}, nil
}
