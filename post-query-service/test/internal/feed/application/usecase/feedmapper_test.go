package usecase_test

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func pointer[T any](value T) *T {
	return &value
}

func TestFeedCoverThumbnailURL(t *testing.T) {
	tests := []struct {
		name  string
		media []usecase.FeedMedia
		want  *string
	}{
		{"no media", nil, nil},
		{
			"the first element by order has a thumbnail, whatever the list order",
			[]usecase.FeedMedia{
				{Order: 2, MediaType: "IMAGE", URL: "second.jpg", ThumbnailURL: pointer("second-thumb.jpg")},
				{Order: 1, MediaType: "VIDEO", URL: "first.mp4", ThumbnailURL: pointer("first-thumb.jpg")},
			},
			pointer("first-thumb.jpg"),
		},
		{
			"an IMAGE with no thumbnail falls back to its url",
			[]usecase.FeedMedia{{Order: 1, MediaType: "IMAGE", URL: "first.jpg"}},
			pointer("first.jpg"),
		},
		{
			"a VIDEO with no thumbnail has no usable value, even if a later element does",
			[]usecase.FeedMedia{
				{Order: 1, MediaType: "VIDEO", URL: "first.mp4"},
				{Order: 2, MediaType: "IMAGE", URL: "second.jpg", ThumbnailURL: pointer("second-thumb.jpg")},
			},
			nil,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := usecase.FeedCoverThumbnailURL(test.media)
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("FeedCoverThumbnailURL() = %v, want %v", deref(got), deref(test.want))
			}
		})
	}
}

func deref(value *string) string {
	if value == nil {
		return "<nil>"
	}
	return *value
}

func TestFeedDurationMS(t *testing.T) {
	tests := []struct {
		name  string
		media []usecase.FeedMedia
		want  *int64
	}{
		{"no media", nil, nil},
		{"an image-only post has none", []usecase.FeedMedia{{MediaType: "IMAGE"}, {MediaType: "IMAGE"}}, nil},
		{
			"it sums the VIDEO durations without re-scaling or re-rounding them",
			[]usecase.FeedMedia{
				{MediaType: "VIDEO", DurationMS: pointer(int64(15000))},
				{MediaType: "IMAGE"},
				{MediaType: "VIDEO", DurationMS: pointer(int64(7000))},
			},
			pointer(int64(22000)),
		},
		{"a value that is not a round second stays as it is", []usecase.FeedMedia{{MediaType: "VIDEO", DurationMS: pointer(int64(1234))}}, pointer(int64(1234))},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := usecase.FeedDurationMS(test.media)
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("FeedDurationMS() = %v, want %v", got, test.want)
			}
		})
	}
}

func samplePost() usecase.FeedPost {
	return usecase.FeedPostFromDocument(usecase.FeedPostDocument{
		PostID:      "post-1",
		UserID:      "creator-1",
		PostType:    "BASIC",
		Description: "Sunset",
		Tags:        []string{"travel", "sunset"},
		Media: []usecase.FeedMediaDocument{
			{ID: "m2", URL: "b.mp4", ThumbnailURL: pointer("b-thumb.jpg"), MediaType: "VIDEO", Order: 2, DurationMS: pointer(int64(15000))},
			{ID: "m1", URL: "a.jpg", MediaType: "IMAGE", Order: 1, TaggedUsers: []string{"ana"}},
		},
		Stats:     usecase.FeedStatsDocument{LikeCount: 1, CommentCount: 2, ShareCount: 3},
		CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
	})
}

func TestFeedPostFromDocument_TurnsEmptyStringsIntoNullsAndSortsMedia(t *testing.T) {
	post := usecase.FeedPostFromDocument(usecase.FeedPostDocument{
		Media: []usecase.FeedMediaDocument{{ID: "b", Order: 2}, {ID: "a", Order: 1}},
	})

	if post.Description != nil || post.CollabID != nil {
		t.Fatalf("Description = %v, CollabID = %v, want both null", post.Description, post.CollabID)
	}
	if post.Tags == nil || post.Media[0].TaggedUsers == nil {
		t.Fatal("lists must never be null")
	}
	if post.Media[0].ID != "a" || post.Media[1].ID != "b" {
		t.Fatalf("media = %+v, want it sorted by order", post.Media)
	}
}

// The stored shape is checked by its BSON field names, not through the Go struct,
// since the struct tags are exactly what could be wrong.
func TestFeedUnitToDocument_PersistsEveryAgreedFieldWithItsExactName(t *testing.T) {
	ingestedAt := time.Date(2026, 3, 2, 10, 0, 0, 0, time.UTC)
	creator := usecase.FeedCreator{UserID: "creator-1", Username: "ana", PictureURL: pointer("ana.jpg")}
	unit := usecase.FeedUnitFromPost("viewer-1", 7, samplePost(), creator, ingestedAt)

	raw, err := bson.Marshal(usecase.FeedUnitToDocument(unit))
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	var stored bson.M
	if err := bson.Unmarshal(raw, &stored); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}

	assertKeys(t, "feed unit", stored, "user_id", "post_id", "position", "creator", "description", "post_type", "tags",
		"collab_id", "media", "cover_thumbnail_url", "duration_ms", "stats", "post_created_at", "ingested_at", "served_at")
	assertKeys(t, "creator", asMap(t, stored["creator"]), "user_id", "username", "picture_url")
	assertKeys(t, "stats", asMap(t, stored["stats"]), "like_count", "comment_count", "share_count")

	media := stored["media"].(bson.A)
	assertKeys(t, "media element", asMap(t, media[0]), "id", "url", "thumbnail_url", "media_type", "order", "duration_ms", "tagged_users")

	if tags := stored["tags"].(bson.A); len(tags) != 2 || tags[0] != "travel" {
		t.Fatalf("tags = %v", tags)
	}
	if stored["position"] != int64(7) || stored["served_at"] != nil {
		t.Fatalf("position = %v, served_at = %v", stored["position"], stored["served_at"])
	}
	if stored["duration_ms"] != int64(15000) {
		t.Fatalf("duration_ms = %v, want the stored value unchanged", stored["duration_ms"])
	}
	if stored["cover_thumbnail_url"] != "a.jpg" {
		t.Fatalf("cover_thumbnail_url = %v, want the first element's url", stored["cover_thumbnail_url"])
	}
}

// asMap turns a nested stored document, which the driver decodes as a bson.D, into a map.
func asMap(t *testing.T, value any) map[string]any {
	t.Helper()

	document, ok := value.(bson.D)
	if !ok {
		t.Fatalf("value = %T, want a nested document", value)
	}
	fields := make(map[string]any, len(document))
	for _, element := range document {
		fields[element.Key] = element.Value
	}
	return fields
}

func assertKeys(t *testing.T, what string, document map[string]any, want ...string) {
	t.Helper()

	got := make([]string, 0, len(document))
	for key := range document {
		got = append(got, key)
	}
	slices.Sort(got)
	slices.Sort(want)
	if !slices.Equal(got, want) {
		t.Fatalf("%s keys = %v, want exactly %v", what, got, want)
	}
}

func TestFeedPageToResponse_UsesTheExactAgreedSchema(t *testing.T) {
	creator := usecase.FeedCreator{UserID: "creator-1", Username: "ana"}
	collab := &usecase.FeedCollab{CollabID: "collab-1", PostID: "post-1", Title: pointer("Harbour"), Status: pointer("OPEN")}
	ref := usecase.FeedUnitRef{PostID: "post-1", Position: 7, PostCreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)}
	viewer := usecase.FeedViewerState{Liked: true, Shared: false, FollowingCreator: true}

	page := usecase.ClaimFeedOutput{
		Items:      []usecase.FeedCard{usecase.FeedCardFromUnit(ref, samplePost(), creator, collab, viewer)},
		LastSeenID: pointer("post-1"),
		HasMore:    true,
	}

	response, err := usecase.FeedPageToResponse(context.Background(), page, unsignedURLs{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}
	raw, err := json.Marshal(response)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}

	assertKeys(t, "page", body, "items", "lastSeenId", "hasMore")
	card := body["items"].([]any)[0].(map[string]any)
	assertKeys(t, "card", card, "postId", "position", "creator", "description", "postType", "postTags", "collabId", "collab",
		"createdAt", "media", "coverThumbnailUrl", "durationMs", "stats", "viewer")
	assertKeys(t, "creator", card["creator"].(map[string]any), "userId", "username", "pictureUrl")
	assertKeys(t, "collab", card["collab"].(map[string]any), "title", "status", "pendingRequestCount")
	assertKeys(t, "stats", card["stats"].(map[string]any), "likeCount", "commentCount", "shareCount")
	assertKeys(t, "viewer", card["viewer"].(map[string]any), "liked", "shared", "followingCreator", "collabRequestStatus")

	media := card["media"].([]any)
	assertKeys(t, "media element", media[0].(map[string]any), "id", "url", "thumbnailUrl", "mediaType", "order", "durationMs", "taggedUsers")

	if media[0].(map[string]any)["id"] != "m1" || media[1].(map[string]any)["id"] != "m2" {
		t.Fatalf("media = %v, want it sorted by order", media)
	}
	if media[0].(map[string]any)["durationMs"] != nil || media[0].(map[string]any)["thumbnailUrl"] != nil {
		t.Fatalf("image media = %v, want null durationMs and thumbnailUrl", media[0])
	}
	if tags := card["postTags"].([]any); len(tags) != 2 || tags[0] != "travel" {
		t.Fatalf("postTags = %v", tags)
	}
	if card["durationMs"] != float64(15000) {
		t.Fatalf("durationMs = %v, want the stored value unchanged", card["durationMs"])
	}
	if card["postType"] != "COLLAB" || card["collabId"] != "collab-1" {
		t.Fatalf("postType = %v, collabId = %v: a resolved collab makes the post COLLAB", card["postType"], card["collabId"])
	}
}

func TestFeedPageToResponse_EmptyPageHasAnEmptyListAndNoCursor(t *testing.T) {
	response, err := usecase.FeedPageToResponse(context.Background(), usecase.ClaimFeedOutput{}, unsignedURLs{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}
	raw, err := json.Marshal(response)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	if want := `{"items":[],"lastSeenId":null,"hasMore":false}`; string(raw) != want {
		t.Fatalf("empty page = %s, want %s", raw, want)
	}
}

func TestFeedCardFromUnit_BasicPostHasNullCollabFields(t *testing.T) {
	ref := usecase.FeedUnitRef{PostID: "post-1"}
	card := usecase.FeedCardFromUnit(ref, samplePost(), usecase.FeedCreator{}, nil, usecase.FeedViewerState{})

	if card.PostType != "BASIC" || card.CollabID != nil || card.CollabTitle != nil || card.CollabStatus != nil {
		t.Fatalf("card = %+v, want BASIC with null collab fields", card)
	}
}

// unsignedURLs stands in for the media signer: it returns every URL as it is.
type unsignedURLs struct{}

func (unsignedURLs) SignReadURL(_ context.Context, rawURL string) (string, error) {
	return rawURL, nil
}

// markingSigner marks every URL it signs, so a test can tell which fields went through
// it.
type markingSigner struct{ err error }

func (s markingSigner) SignReadURL(_ context.Context, rawURL string) (string, error) {
	if s.err != nil {
		return "", s.err
	}
	return rawURL + "?sig=signed", nil
}

func TestFeedPageToResponse_SignsEveryBlobURLAndKeepsNullsNull(t *testing.T) {
	creator := usecase.FeedCreator{UserID: "creator-1", Username: "ana", PictureURL: pointer("ana.jpg")}
	ref := usecase.FeedUnitRef{PostID: "post-1"}
	page := usecase.ClaimFeedOutput{Items: []usecase.FeedCard{
		usecase.FeedCardFromUnit(ref, samplePost(), creator, nil, usecase.FeedViewerState{}),
		usecase.FeedCardFromUnit(usecase.FeedUnitRef{PostID: "post-2"}, usecase.FeedPost{PostID: "post-2"}, usecase.FeedCreator{UserID: "creator-2"}, nil, usecase.FeedViewerState{}),
	}}

	response, err := usecase.FeedPageToResponse(context.Background(), page, markingSigner{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}

	card := response.Items[0]
	if card.Media[0].URL != "a.jpg?sig=signed" || card.Media[1].URL != "b.mp4?sig=signed" {
		t.Fatalf("media urls = %q, %q, want both signed", card.Media[0].URL, card.Media[1].URL)
	}
	if card.Media[0].ThumbnailURL != nil {
		t.Fatalf("image thumbnailUrl = %q, want null to stay null", *card.Media[0].ThumbnailURL)
	}
	if card.Media[1].ThumbnailURL == nil || *card.Media[1].ThumbnailURL != "b-thumb.jpg?sig=signed" {
		t.Fatalf("video thumbnailUrl = %v, want it signed", card.Media[1].ThumbnailURL)
	}
	if card.CoverThumbnailURL == nil || *card.CoverThumbnailURL != "a.jpg?sig=signed" {
		t.Fatalf("coverThumbnailUrl = %v, want the first image signed", card.CoverThumbnailURL)
	}
	if card.Creator.PictureURL == nil || *card.Creator.PictureURL != "ana.jpg?sig=signed" {
		t.Fatalf("creator.pictureUrl = %v, want it signed", card.Creator.PictureURL)
	}

	empty := response.Items[1]
	if empty.CoverThumbnailURL != nil || empty.Creator.PictureURL != nil || len(empty.Media) != 0 {
		t.Fatalf("card without media or picture = %+v, want null urls and no media", empty)
	}
}

func TestFeedPageToResponse_DoesNotChangeTheStoredURLs(t *testing.T) {
	post := samplePost()
	page := usecase.ClaimFeedOutput{Items: []usecase.FeedCard{
		usecase.FeedCardFromUnit(usecase.FeedUnitRef{PostID: "post-1"}, post, usecase.FeedCreator{PictureURL: pointer("ana.jpg")}, nil, usecase.FeedViewerState{}),
	}}

	if _, err := usecase.FeedPageToResponse(context.Background(), page, markingSigner{}); err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}

	card := page.Items[0]
	for _, url := range []string{card.Media[0].URL, *card.Media[1].ThumbnailURL, *card.CoverThumbnailURL, *card.Creator.PictureURL} {
		if strings.Contains(url, "sig=") {
			t.Fatalf("card url %q was signed in place, want the bare url kept", url)
		}
	}
}

func TestFeedPageToResponse_ReturnsTheSignerError(t *testing.T) {
	signErr := errors.New("user delegation key unavailable")
	page := usecase.ClaimFeedOutput{Items: []usecase.FeedCard{
		usecase.FeedCardFromUnit(usecase.FeedUnitRef{PostID: "post-1"}, samplePost(), usecase.FeedCreator{}, nil, usecase.FeedViewerState{}),
	}}

	if _, err := usecase.FeedPageToResponse(context.Background(), page, markingSigner{err: signErr}); !errors.Is(err, signErr) {
		t.Fatalf("FeedPageToResponse() error = %v, want %v", err, signErr)
	}
}

func TestFeedPageToResponse_RequiresASigner(t *testing.T) {
	if _, err := usecase.FeedPageToResponse(context.Background(), usecase.ClaimFeedOutput{}, nil); err == nil {
		t.Fatal("FeedPageToResponse() with no signer error = nil, want an error")
	}
}
