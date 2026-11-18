package usecase_test

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func toJSONMap(t *testing.T, value any) map[string]any {
	t.Helper()

	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return body
}

func samplePostViewCard() usecase.FeedCard {
	creator := usecase.FeedCreator{UserID: "creator-1", Username: "ana", PictureURL: pointer("ana.jpg")}
	collab := &usecase.FeedCollab{CollabID: "collab-1", PostID: "post-1", Title: pointer("Harbour"), Status: pointer("OPEN")}
	viewer := usecase.FeedViewerState{Liked: true, Shared: false, FollowingCreator: true}
	return usecase.FeedCardFromPost(samplePost(), creator, collab, viewer)
}

func TestPostViewToResponse_MapsAStoredPostToPostViewWithoutPosition(t *testing.T) {
	view, err := usecase.PostViewToResponse(context.Background(), samplePostViewCard(), unsignedURLs{})
	if err != nil {
		t.Fatalf("PostViewToResponse() error = %v", err)
	}
	body := toJSONMap(t, view)

	assertKeys(t, "post view", body, "postId", "creator", "description", "postType", "postTags", "collabId", "collab",
		"createdAt", "media", "coverThumbnailUrl", "durationMs", "stats", "viewer")
	assertKeys(t, "creator", body["creator"].(map[string]any), "userId", "username", "pictureUrl")
	assertKeys(t, "collab", body["collab"].(map[string]any), "title", "status", "pendingRequestCount")
	assertKeys(t, "stats", body["stats"].(map[string]any), "likeCount", "commentCount", "shareCount")
	assertKeys(t, "viewer", body["viewer"].(map[string]any), "liked", "shared", "followingCreator", "collabRequestStatus")
	assertKeys(t, "media element", body["media"].([]any)[0].(map[string]any), "id", "url", "thumbnailUrl", "mediaType", "order", "durationMs", "taggedUsers")

	if body["postId"] != "post-1" || body["createdAt"] != "2026-03-01T10:00:00Z" || body["description"] != "Sunset" {
		t.Fatalf("post view = %v", body)
	}
	if body["postType"] != "COLLAB" || body["collabId"] != "collab-1" {
		t.Fatalf("postType = %v, collabId = %v", body["postType"], body["collabId"])
	}
	collab := body["collab"].(map[string]any)
	if collab["title"] != "Harbour" || collab["status"] != "OPEN" || collab["pendingRequestCount"] != nil {
		t.Fatalf("collab = %v, want pendingRequestCount null until task 52", collab)
	}
	viewer := body["viewer"].(map[string]any)
	if viewer["liked"] != true || viewer["shared"] != false || viewer["followingCreator"] != true || viewer["collabRequestStatus"] != nil {
		t.Fatalf("viewer = %v, want collabRequestStatus null until task 52", viewer)
	}
}

// The post view is the feed item without position: the same post must give the same
// values in both shapes, which is what one shared mapper guarantees.
func TestPostViewToResponse_IsTheFeedItemWithoutPosition(t *testing.T) {
	card := samplePostViewCard()
	card.Position = 9

	view, err := usecase.PostViewToResponse(context.Background(), card, markingSigner{})
	if err != nil {
		t.Fatalf("PostViewToResponse() error = %v", err)
	}
	page, err := usecase.FeedPageToResponse(context.Background(), usecase.ClaimFeedOutput{Items: []usecase.FeedCard{card}}, markingSigner{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}

	item := toJSONMap(t, page.Items[0])
	delete(item, "position")
	got := toJSONMap(t, view)
	delete(got["collab"].(map[string]any), "pendingRequestCount")
	delete(got["viewer"].(map[string]any), "collabRequestStatus")

	if !reflect.DeepEqual(got, item) {
		t.Fatalf("post view = %v\nfeed item without position = %v", got, item)
	}
}

func TestPostViewToResponse_SignsEveryBlobURLAndKeepsNullsNull(t *testing.T) {
	view, err := usecase.PostViewToResponse(context.Background(), samplePostViewCard(), markingSigner{})
	if err != nil {
		t.Fatalf("PostViewToResponse() error = %v", err)
	}

	if view.Media[0].URL != "a.jpg?sig=signed" || view.Media[1].URL != "b.mp4?sig=signed" {
		t.Fatalf("media urls = %q, %q, want both signed", view.Media[0].URL, view.Media[1].URL)
	}
	if view.Media[0].ThumbnailURL != nil || view.Media[1].ThumbnailURL == nil || *view.Media[1].ThumbnailURL != "b-thumb.jpg?sig=signed" {
		t.Fatalf("thumbnail urls = %v, %v, want null kept and the video's signed", view.Media[0].ThumbnailURL, view.Media[1].ThumbnailURL)
	}
	if view.CoverThumbnailURL == nil || *view.CoverThumbnailURL != "a.jpg?sig=signed" {
		t.Fatalf("coverThumbnailUrl = %v, want it signed", view.CoverThumbnailURL)
	}
	if view.Creator.PictureURL == nil || *view.Creator.PictureURL != "ana.jpg?sig=signed" {
		t.Fatalf("creator.pictureUrl = %v, want it signed", view.Creator.PictureURL)
	}
}

func TestPostViewToResponse_ReturnsTheSignerErrorAndRequiresASigner(t *testing.T) {
	signErr := errors.New("user delegation key unavailable")
	if _, err := usecase.PostViewToResponse(context.Background(), samplePostViewCard(), markingSigner{err: signErr}); !errors.Is(err, signErr) {
		t.Fatalf("PostViewToResponse() error = %v, want %v", err, signErr)
	}
	if _, err := usecase.PostViewToResponse(context.Background(), samplePostViewCard(), nil); err == nil {
		t.Fatal("PostViewToResponse() with no signer error = nil, want an error")
	}
}
