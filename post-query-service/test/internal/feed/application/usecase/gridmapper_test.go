package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// gridPost is a stored post with a video ordered after an image, stored out of order.
func gridPost() usecase.FeedPost {
	return usecase.FeedPostFromDocument(usecase.FeedPostDocument{
		PostID:   "post-1",
		UserID:   "creator-1",
		PostType: usecase.FeedPostTypeBasic,
		Media: []usecase.FeedMediaDocument{
			{ID: "m-3", URL: "c.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 3},
			{ID: "m-2", URL: "b.mp4", ThumbnailURL: pointer("b-thumb.jpg"), MediaType: usecase.FeedMediaTypeVideo, Order: 1, DurationMS: pointer(int64(15000))},
			{ID: "m-1", URL: "a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 2},
		},
		Stats:     usecase.FeedStatsDocument{LikeCount: 7, CommentCount: 2, ShareCount: 1, ViewCount: 12},
		CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
	})
}

func TestGridItemFromPost_MapsAPostToGridItemWithTheFirstMediaTypeAndMediaCount(t *testing.T) {
	item, ok := usecase.GridItemFromPost(gridPost(), nil)

	if !ok {
		t.Fatal("GridItemFromPost() = false, want an item")
	}
	want := usecase.GridItem{
		PostID:            "post-1",
		CoverThumbnailURL: pointer("b-thumb.jpg"),
		MediaType:         usecase.FeedMediaTypeVideo,
		MediaCount:        3,
		PostType:          usecase.FeedPostTypeBasic,
		LikeCount:         7,
		ViewCount:         12,
		CreatedAt:         time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
	}
	if item.CoverThumbnailURL == nil || *item.CoverThumbnailURL != *want.CoverThumbnailURL || item.CollabStatus != nil {
		t.Fatalf("cover = %v, collab status = %v, want the first media's thumbnail and no collab", item.CoverThumbnailURL, item.CollabStatus)
	}
	item.CoverThumbnailURL, want.CoverThumbnailURL = nil, nil
	if item != want {
		t.Fatalf("GridItemFromPost() = %+v, want %+v", item, want)
	}
}

func TestGridItemFromPost_TakesTheCollabTheWayAFeedCardDoes(t *testing.T) {
	post := gridPost()
	collab := &usecase.FeedCollab{CollabID: "collab-1", PostID: "post-1", Title: pointer("Sunsets"), Status: pointer("OPEN")}

	item, _ := usecase.GridItemFromPost(post, collab)
	card := usecase.FeedCardFromPost(post, usecase.FeedCreator{}, collab, usecase.FeedViewerState{})

	if item.PostType != usecase.FeedPostTypeCollab || item.PostType != card.PostType {
		t.Fatalf("post type = %q, want COLLAB like the card (%q)", item.PostType, card.PostType)
	}
	if item.CollabStatus == nil || *item.CollabStatus != "OPEN" {
		t.Fatalf("collab status = %v, want OPEN", item.CollabStatus)
	}
}

func TestGridItemFromPost_LeavesOutAPostWithoutMedia(t *testing.T) {
	post := gridPost()
	post.Media = nil

	if _, ok := usecase.GridItemFromPost(post, nil); ok {
		t.Fatal("GridItemFromPost() = true for a post without media")
	}
}

func TestGridPageToResponse_UsesTheExactAgreedSchemaAndSignsTheCover(t *testing.T) {
	withCover, _ := usecase.GridItemFromPost(gridPost(), &usecase.FeedCollab{CollabID: "collab-1", PostID: "post-1", Status: pointer("CLOSED")})
	noCover := usecase.GridItem{PostID: "post-2", MediaType: usecase.FeedMediaTypeVideo, MediaCount: 1, PostType: usecase.FeedPostTypeBasic,
		CreatedAt: time.Date(2026, 3, 2, 10, 0, 0, 0, time.UTC)}

	response, err := usecase.GridPageToResponse(context.Background(), usecase.GridPage{Items: []usecase.GridItem{withCover, noCover}, NextCursor: pointer("next")}, markingSigner{})
	if err != nil {
		t.Fatalf("GridPageToResponse() error = %v", err)
	}

	body := toJSONMap(t, response)
	assertKeys(t, "page", body, "items", "nextCursor")
	items := body["items"].([]any)
	first, second := items[0].(map[string]any), items[1].(map[string]any)
	assertKeys(t, "grid item", first, "postId", "coverThumbnailUrl", "mediaType", "mediaCount", "postType", "collabStatus", "likeCount", "viewCount", "createdAt")
	if first["postId"] != "post-1" || first["coverThumbnailUrl"] != "b-thumb.jpg?sig=signed" || first["mediaType"] != "VIDEO" || first["mediaCount"] != float64(3) {
		t.Fatalf("first item = %v", first)
	}
	if first["postType"] != "COLLAB" || first["collabStatus"] != "CLOSED" || first["likeCount"] != float64(7) || first["viewCount"] != float64(12) || first["createdAt"] != "2026-03-01T10:00:00Z" {
		t.Fatalf("first item = %v", first)
	}
	if second["coverThumbnailUrl"] != nil || second["collabStatus"] != nil || second["postType"] != "BASIC" {
		t.Fatalf("second item = %v, want null cover and collab status", second)
	}
	if viewCount, present := second["viewCount"]; !present || viewCount != float64(0) {
		t.Fatalf("second item viewCount = %v (present %v), want 0, never omitted", viewCount, present)
	}
	if body["nextCursor"] != "next" {
		t.Fatalf("nextCursor = %v", body["nextCursor"])
	}
}

func TestGridPageToResponse_AnswersAnEmptyListAndANullCursor(t *testing.T) {
	response, err := usecase.GridPageToResponse(context.Background(), usecase.GridPage{}, unsignedURLs{})
	if err != nil {
		t.Fatalf("GridPageToResponse() error = %v", err)
	}

	body := toJSONMap(t, response)
	if items, ok := body["items"].([]any); !ok || len(items) != 0 || body["nextCursor"] != nil {
		t.Fatalf("body = %v, want items [] and nextCursor null", body)
	}
}

func TestGridPageToResponse_ReturnsTheSignerErrorAndRequiresASigner(t *testing.T) {
	item, _ := usecase.GridItemFromPost(gridPost(), nil)
	page := usecase.GridPage{Items: []usecase.GridItem{item}}
	wantErr := errors.New("no key")

	if _, err := usecase.GridPageToResponse(context.Background(), page, markingSigner{err: wantErr}); !errors.Is(err, wantErr) {
		t.Fatalf("GridPageToResponse() error = %v, want %v", err, wantErr)
	}
	if _, err := usecase.GridPageToResponse(context.Background(), page, nil); err == nil {
		t.Fatal("GridPageToResponse() without a signer did not fail")
	}
}

func TestGridItemFromPost_MapsStatsViewCountToViewCount(t *testing.T) {
	item, _ := usecase.GridItemFromPost(gridPost(), nil)

	if item.ViewCount != 12 {
		t.Fatalf("ViewCount = %d, want stats.view_count (12)", item.ViewCount)
	}
	response, err := usecase.GridPageToResponse(context.Background(), usecase.GridPage{Items: []usecase.GridItem{item}}, unsignedURLs{})
	if err != nil {
		t.Fatalf("GridPageToResponse() error = %v", err)
	}
	if got := response.Items[0].ViewCount; got != 12 {
		t.Fatalf("response viewCount = %d, want 12", got)
	}
}

func TestGridItemFromPost_ReturnsViewCountZeroForAPostWithoutStatsViewCount(t *testing.T) {
	// The stored shape of a post projected before task 63: stats without view_count.
	raw, err := bson.Marshal(bson.D{
		{Key: "post_id", Value: "post-legacy"},
		{Key: "post_type", Value: usecase.FeedPostTypeBasic},
		{Key: "media", Value: bson.A{bson.D{{Key: "id", Value: "m-1"}, {Key: "url", Value: "a.jpg"}, {Key: "media_type", Value: usecase.FeedMediaTypeImage}, {Key: "order", Value: 1}}}},
		{Key: "stats", Value: bson.D{{Key: "like_count", Value: int64(3)}, {Key: "comment_count", Value: int64(0)}, {Key: "share_count", Value: int64(0)}}},
		{Key: "created_at", Value: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)},
	})
	if err != nil {
		t.Fatalf("bson.Marshal() error = %v", err)
	}
	var document usecase.FeedPostDocument
	if err := bson.Unmarshal(raw, &document); err != nil {
		t.Fatalf("bson.Unmarshal() error = %v", err)
	}

	item, ok := usecase.GridItemFromPost(usecase.FeedPostFromDocument(document), nil)
	if !ok {
		t.Fatal("GridItemFromPost() = false, want an item")
	}
	if item.ViewCount != 0 || item.LikeCount != 3 {
		t.Fatalf("viewCount/likeCount = %d/%d, want 0/3", item.ViewCount, item.LikeCount)
	}
	response, err := usecase.GridPageToResponse(context.Background(), usecase.GridPage{Items: []usecase.GridItem{item}}, unsignedURLs{})
	if err != nil {
		t.Fatalf("GridPageToResponse() error = %v", err)
	}
	body := toJSONMap(t, response)
	first := body["items"].([]any)[0].(map[string]any)
	if viewCount, present := first["viewCount"]; !present || viewCount != float64(0) {
		t.Fatalf("viewCount = %v (present %v), want 0", viewCount, present)
	}
}

func TestOwnTabPageToResponse_MapsViewCountOnLikedAndHistoryItems(t *testing.T) {
	page := usecase.TimedGridPage{Items: []usecase.TimedGridItem{{GridItem: usecase.GridItem{PostID: "post-1", MediaType: "IMAGE", MediaCount: 1, PostType: "BASIC", ViewCount: 5}}}}

	liked, err := usecase.LikedPageToResponse(context.Background(), page, unsignedURLs{})
	if err != nil {
		t.Fatalf("LikedPageToResponse() error = %v", err)
	}
	history, err := usecase.HistoryPageToResponse(context.Background(), page, unsignedURLs{})
	if err != nil {
		t.Fatalf("HistoryPageToResponse() error = %v", err)
	}
	if liked.Items[0].ViewCount != 5 || history.Items[0].ViewCount != 5 {
		t.Fatalf("liked/history viewCount = %d/%d, want 5/5", liked.Items[0].ViewCount, history.Items[0].ViewCount)
	}
}
