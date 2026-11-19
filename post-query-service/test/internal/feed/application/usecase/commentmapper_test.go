package usecase_test

import (
	"context"
	"errors"
	"maps"
	"testing"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func sampleCommentPage() usecase.CommentPage {
	createdAt := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	return usecase.CommentPage{
		Items: []usecase.CommentItem{
			{
				Comment: usecase.CommentFromDocument(usecase.CommentDocument{
					CommentID: "comment-1", PostID: "post-1", UserID: "author-1", Content: "Lovely light", ReplyTo: "",
					LikeCount: 5, ReplyCount: 3, CreatedAt: createdAt, UpdatedAt: createdAt.Add(time.Minute),
				}),
				Author:    usecase.FeedCreator{UserID: "author-1", Username: "ana", PictureURL: pointer("ana.jpg")},
				LikedByMe: true,
			},
			{
				Comment: usecase.CommentFromDocument(usecase.CommentDocument{
					CommentID: "comment-2", PostID: "post-1", UserID: "author-2", Content: "Agreed", ReplyTo: "comment-1",
					CreatedAt: createdAt, UpdatedAt: createdAt,
				}),
				Author: usecase.FeedCreator{UserID: "author-2", Username: "luis"},
			},
		},
		NextCursor: pointer("next-page"),
	}
}

func TestCommentPageToResponse_UsesTheExactAgreedSchema(t *testing.T) {
	response, err := usecase.CommentPageToResponse(context.Background(), sampleCommentPage(), unsignedURLs{})
	if err != nil {
		t.Fatalf("CommentPageToResponse() error = %v", err)
	}

	body := toJSONMap(t, response)
	assertKeys(t, "page", body, "items", "nextCursor")
	items := body["items"].([]any)
	first := items[0].(map[string]any)
	assertKeys(t, "comment", first, "commentId", "postId", "author", "content", "replyTo", "likeCount", "replyCount", "likedByMe", "createdAt", "updatedAt")
	assertKeys(t, "author", first["author"].(map[string]any), "userId", "username", "pictureUrl")

	if first["commentId"] != "comment-1" || first["postId"] != "post-1" || first["content"] != "Lovely light" || first["replyTo"] != nil {
		t.Fatalf("first comment = %v, want a top-level comment with a null replyTo", first)
	}
	if first["likeCount"] != float64(5) || first["replyCount"] != float64(3) || first["likedByMe"] != true {
		t.Fatalf("counters = %v %v %v", first["likeCount"], first["replyCount"], first["likedByMe"])
	}
	if first["createdAt"] != "2026-03-01T10:00:00Z" || first["updatedAt"] != "2026-03-01T10:01:00Z" {
		t.Fatalf("dates = %v %v", first["createdAt"], first["updatedAt"])
	}
	second := items[1].(map[string]any)
	if second["replyTo"] != "comment-1" || second["likedByMe"] != false || second["author"].(map[string]any)["pictureUrl"] != nil {
		t.Fatalf("second comment = %v, want a reply with a null author picture", second)
	}
	if body["nextCursor"] != "next-page" {
		t.Fatalf("nextCursor = %v", body["nextCursor"])
	}
}

func TestCommentPageToResponse_SignsAuthorPicturesLikeTheFeedCreator(t *testing.T) {
	page := sampleCommentPage()

	response, err := usecase.CommentPageToResponse(context.Background(), page, markingSigner{})
	if err != nil {
		t.Fatalf("CommentPageToResponse() error = %v", err)
	}

	// The same user mapped as a feed card's creator, for comparison.
	card := usecase.FeedCardFromPost(usecase.FeedPost{PostID: "post-1"}, page.Items[0].Author, nil, usecase.FeedViewerState{})
	feed, err := usecase.FeedPageToResponse(context.Background(), usecase.ClaimFeedOutput{Items: []usecase.FeedCard{card}}, markingSigner{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}
	if author, creator := toJSONMap(t, response.Items[0].Author), toJSONMap(t, feed.Items[0].Creator); !maps.Equal(author, creator) {
		t.Fatalf("author = %v, feed creator = %v: one UserSummary mapping", author, creator)
	}
	if got := *response.Items[0].Author.PictureURL; got != "ana.jpg?sig=signed" {
		t.Fatalf("pictureUrl = %q, want it signed", got)
	}
	if response.Items[1].Author.PictureURL != nil {
		t.Fatal("pictureUrl = signed, want null to stay null")
	}
}

func TestCommentPageToResponse_ReturnsAnEmptyListAndRequiresASigner(t *testing.T) {
	response, err := usecase.CommentPageToResponse(context.Background(), usecase.CommentPage{}, unsignedURLs{})
	if err != nil || response.Items == nil || len(response.Items) != 0 || response.NextCursor != nil {
		t.Fatalf("CommentPageToResponse(empty) = %+v, %v, want items [] and a null cursor", response, err)
	}

	if _, err := usecase.CommentPageToResponse(context.Background(), sampleCommentPage(), nil); err == nil {
		t.Fatal("CommentPageToResponse() error = nil without a signer")
	}
	wantErr := errors.New("no delegation key")
	if _, err := usecase.CommentPageToResponse(context.Background(), sampleCommentPage(), markingSigner{err: wantErr}); !errors.Is(err, wantErr) {
		t.Fatalf("CommentPageToResponse() error = %v, want the signer's", err)
	}
}
