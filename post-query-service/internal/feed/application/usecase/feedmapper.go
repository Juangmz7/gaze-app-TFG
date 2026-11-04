package usecase

import (
	"cmp"
	"slices"
	"time"
)

// This file is the single place where feed field names change. It holds the stored
// shapes, the API shape and every conversion between them, so a divergence such as
// the ones below cannot leak into usecases, repositories or handlers:
//
//	Event / API            MongoDB               Note
//	postTags               tags                  in both directions
//	media[].mediaType      media[].media_type    never "type"
//	media[].duration       media[].duration_ms   renamed on the way in; already ms
//	media[].durationMs     media[].duration_ms   API name
//	createdAt              post_created_at       feed_units only (posts: created_at)
//	creator.userId         user_id / creator.user_id
//
// The types carry struct tags but import neither a driver nor net/http, so this
// layer still depends on nothing external. Derived fields:
//
//   - coverThumbnailUrl: thumbnailUrl of the first media element by order, falling
//     back to its url when that element is an IMAGE with no thumbnail, and null when
//     no usable value exists.
//   - durationMs: sum of the VIDEO durations, null when the post has no video. The
//     stored value already holds milliseconds rounded to the second
//     (post-command-service stores seconds * 1000), so it is summed but never
//     re-scaled or re-rounded.

// FeedMediaDocument is one media element as posts and feed_units store it.
type FeedMediaDocument struct {
	ID           string   `bson:"id"`
	URL          string   `bson:"url"`
	ThumbnailURL *string  `bson:"thumbnail_url"`
	MediaType    string   `bson:"media_type"`
	Order        int      `bson:"order"`
	DurationMS   *int64   `bson:"duration_ms"`
	TaggedUsers  []string `bson:"tagged_users"`
}

// FeedStatsDocument holds the counters as posts and feed_units store them.
type FeedStatsDocument struct {
	LikeCount    int64 `bson:"like_count"`
	CommentCount int64 `bson:"comment_count"`
	ShareCount   int64 `bson:"share_count"`
}

// FeedCreatorDocument is the creator as feed_units stores it.
type FeedCreatorDocument struct {
	UserID     string  `bson:"user_id"`
	Username   string  `bson:"username"`
	PictureURL *string `bson:"picture_url"`
}

// FeedUnitDocument is the persisted shape of a feed_units document.
type FeedUnitDocument struct {
	UserID            string              `bson:"user_id"`
	PostID            string              `bson:"post_id"`
	Position          int64               `bson:"position"`
	Creator           FeedCreatorDocument `bson:"creator"`
	Description       *string             `bson:"description"`
	PostType          string              `bson:"post_type"`
	Tags              []string            `bson:"tags"`
	CollabID          *string             `bson:"collab_id"`
	Media             []FeedMediaDocument `bson:"media"`
	CoverThumbnailURL *string             `bson:"cover_thumbnail_url"`
	DurationMS        *int64              `bson:"duration_ms"`
	Stats             FeedStatsDocument   `bson:"stats"`
	PostCreatedAt     time.Time           `bson:"post_created_at"`
	IngestedAt        time.Time           `bson:"ingested_at"`
	ServedAt          *time.Time          `bson:"served_at"`
}

// FeedPostDocument is the part of a posts document the feed reads. The posts read
// model stores an absent description or collab as an empty string.
type FeedPostDocument struct {
	PostID      string              `bson:"post_id"`
	UserID      string              `bson:"user_id"`
	CollabID    string              `bson:"collab_id"`
	PostType    string              `bson:"post_type"`
	Description string              `bson:"description"`
	Tags        []string            `bson:"tags"`
	Media       []FeedMediaDocument `bson:"media"`
	Stats       FeedStatsDocument   `bson:"stats"`
	CreatedAt   time.Time           `bson:"created_at"`
}

// FeedUserDocument is the part of a users document the feed reads. The users read
// model stores an absent picture as an empty string.
type FeedUserDocument struct {
	UserID     string `bson:"user_id"`
	Username   string `bson:"username"`
	PictureURL string `bson:"picture_url"`
}

// FeedCollabDocument is the part of a post_collabs document the feed reads.
type FeedCollabDocument struct {
	CollabID  string    `bson:"collab_id"`
	PostID    string    `bson:"post_id"`
	Title     string    `bson:"title"`
	Status    string    `bson:"status"`
	CreatedAt time.Time `bson:"created_at"`
}

// FeedCreatorResponse is the API shape of a card's creator.
type FeedCreatorResponse struct {
	UserID     string  `json:"userId"`
	Username   string  `json:"username"`
	PictureURL *string `json:"pictureUrl"`
}

// FeedCollabResponse is the API shape of a card's collab. Both fields are null for
// a BASIC post.
type FeedCollabResponse struct {
	Title  *string `json:"title"`
	Status *string `json:"status"`
}

// FeedMediaResponse is the API shape of one media element.
type FeedMediaResponse struct {
	ID           string   `json:"id"`
	URL          string   `json:"url"`
	ThumbnailURL *string  `json:"thumbnailUrl"`
	MediaType    string   `json:"mediaType"`
	Order        int      `json:"order"`
	DurationMS   *int64   `json:"durationMs"`
	TaggedUsers  []string `json:"taggedUsers"`
}

// FeedStatsResponse is the API shape of a card's counters.
type FeedStatsResponse struct {
	LikeCount    int64 `json:"likeCount"`
	CommentCount int64 `json:"commentCount"`
	ShareCount   int64 `json:"shareCount"`
}

// FeedViewerResponse is the API shape of the viewer's state.
type FeedViewerResponse struct {
	Liked            bool `json:"liked"`
	Shared           bool `json:"shared"`
	FollowingCreator bool `json:"followingCreator"`
}

// FeedCardResponse is the API shape of one feed card. Internal fields such as
// served_at or ingested_at have no place here.
type FeedCardResponse struct {
	PostID            string              `json:"postId"`
	Position          int64               `json:"position"`
	Creator           FeedCreatorResponse `json:"creator"`
	Description       *string             `json:"description"`
	PostType          string              `json:"postType"`
	PostTags          []string            `json:"postTags"`
	CollabID          *string             `json:"collabId"`
	Collab            FeedCollabResponse  `json:"collab"`
	CreatedAt         time.Time           `json:"createdAt"`
	Media             []FeedMediaResponse `json:"media"`
	CoverThumbnailURL *string             `json:"coverThumbnailUrl"`
	DurationMS        *int64              `json:"durationMs"`
	Stats             FeedStatsResponse   `json:"stats"`
	Viewer            FeedViewerResponse  `json:"viewer"`
}

// FeedPageResponse is the API shape of a claim response.
type FeedPageResponse struct {
	Items      []FeedCardResponse `json:"items"`
	LastSeenID *string            `json:"lastSeenId"`
	HasMore    bool               `json:"hasMore"`
}

// FeedCoverThumbnailURL derives coverThumbnailUrl. See the file comment.
func FeedCoverThumbnailURL(media []FeedMedia) *string {
	if len(media) == 0 {
		return nil
	}

	first := slices.MinFunc(media, func(a, b FeedMedia) int { return cmp.Compare(a.Order, b.Order) })
	if first.ThumbnailURL != nil && *first.ThumbnailURL != "" {
		return first.ThumbnailURL
	}
	if first.MediaType == FeedMediaTypeImage && first.URL != "" {
		url := first.URL
		return &url
	}

	return nil
}

// FeedDurationMS derives durationMs. See the file comment.
func FeedDurationMS(media []FeedMedia) *int64 {
	var total int64
	hasVideo := false
	for _, element := range media {
		if element.MediaType != FeedMediaTypeVideo {
			continue
		}
		hasVideo = true
		if element.DurationMS != nil {
			total += *element.DurationMS
		}
	}
	if !hasVideo {
		return nil
	}

	return &total
}

// FeedPostFromDocument maps a posts document, sorting its media by order and turning
// the read model's empty strings into nulls.
func FeedPostFromDocument(document FeedPostDocument) FeedPost {
	return FeedPost{
		PostID:      document.PostID,
		CreatorID:   document.UserID,
		CollabID:    nullIfEmpty(document.CollabID),
		PostType:    document.PostType,
		Description: nullIfEmpty(document.Description),
		Tags:        emptyIfNull(document.Tags),
		Media:       feedMediaFromDocuments(document.Media),
		Stats:       feedStatsFromDocument(document.Stats),
		CreatedAt:   document.CreatedAt,
	}
}

// FeedCreatorFromDocument maps a users document.
func FeedCreatorFromDocument(document FeedUserDocument) FeedCreator {
	return FeedCreator{
		UserID:     document.UserID,
		Username:   document.Username,
		PictureURL: nullIfEmpty(document.PictureURL),
	}
}

// FeedCollabFromDocument maps a post_collabs document.
func FeedCollabFromDocument(document FeedCollabDocument) FeedCollab {
	return FeedCollab{
		CollabID:  document.CollabID,
		PostID:    document.PostID,
		Title:     nullIfEmpty(document.Title),
		Status:    nullIfEmpty(document.Status),
		CreatedAt: document.CreatedAt,
	}
}

// FeedUnitFromPost builds the snapshot stored for userID at position.
func FeedUnitFromPost(userID string, position int64, post FeedPost, creator FeedCreator, ingestedAt time.Time) FeedUnit {
	return FeedUnit{
		UserID:            userID,
		PostID:            post.PostID,
		Position:          position,
		Creator:           creator,
		Description:       post.Description,
		PostType:          post.PostType,
		Tags:              post.Tags,
		CollabID:          post.CollabID,
		Media:             post.Media,
		CoverThumbnailURL: FeedCoverThumbnailURL(post.Media),
		DurationMS:        FeedDurationMS(post.Media),
		Stats:             post.Stats,
		PostCreatedAt:     post.CreatedAt,
		IngestedAt:        ingestedAt,
	}
}

// FeedUnitToDocument maps a unit to its stored shape. served_at starts null, which
// is what makes a unit "unserved" and keeps it out of reach of the TTL index.
func FeedUnitToDocument(unit FeedUnit) FeedUnitDocument {
	return FeedUnitDocument{
		UserID:   unit.UserID,
		PostID:   unit.PostID,
		Position: unit.Position,
		Creator: FeedCreatorDocument{
			UserID:     unit.Creator.UserID,
			Username:   unit.Creator.Username,
			PictureURL: unit.Creator.PictureURL,
		},
		Description:       unit.Description,
		PostType:          unit.PostType,
		Tags:              emptyIfNull(unit.Tags),
		CollabID:          unit.CollabID,
		Media:             feedMediaToDocuments(unit.Media),
		CoverThumbnailURL: unit.CoverThumbnailURL,
		DurationMS:        unit.DurationMS,
		Stats: FeedStatsDocument{
			LikeCount:    unit.Stats.LikeCount,
			CommentCount: unit.Stats.CommentCount,
			ShareCount:   unit.Stats.ShareCount,
		},
		PostCreatedAt: unit.PostCreatedAt,
		IngestedAt:    unit.IngestedAt,
		ServedAt:      unit.ServedAt,
	}
}

// FeedUnitRefFromDocument maps the fields a claim reads from a feed_units document.
func FeedUnitRefFromDocument(document FeedUnitDocument) FeedUnitRef {
	return FeedUnitRef{
		PostID:        document.PostID,
		Position:      document.Position,
		PostCreatedAt: document.PostCreatedAt,
	}
}

// FeedCardFromUnit overlays the current read models on a stored unit. The unit only
// contributes what cannot change: its post id, position and the post's creation date.
//
// A collab that resolves makes the post COLLAB whatever posts says: a collab opened
// on an existing post only publishes CollabOpenedEvent, and a linked post only
// changes its collab_id, so the post's own post_type can still read BASIC.
func FeedCardFromUnit(unit FeedUnitRef, post FeedPost, creator FeedCreator, collab *FeedCollab, viewer FeedViewerState) FeedCard {
	card := FeedCard{
		PostID:            unit.PostID,
		Position:          unit.Position,
		Creator:           creator,
		Description:       post.Description,
		PostType:          post.PostType,
		Tags:              post.Tags,
		CollabID:          post.CollabID,
		CreatedAt:         unit.PostCreatedAt,
		Media:             post.Media,
		CoverThumbnailURL: FeedCoverThumbnailURL(post.Media),
		DurationMS:        FeedDurationMS(post.Media),
		Stats:             post.Stats,
		Viewer:            viewer,
	}
	if collab != nil {
		collabID := collab.CollabID
		card.CollabID = &collabID
		card.CollabTitle = collab.Title
		card.CollabStatus = collab.Status
		card.PostType = FeedPostTypeCollab
	}

	return card
}

// FeedPageToResponse maps a claim's result to its API shape, with lists that are
// never null.
func FeedPageToResponse(output ClaimFeedOutput) FeedPageResponse {
	items := make([]FeedCardResponse, 0, len(output.Items))
	for _, card := range output.Items {
		items = append(items, feedCardToResponse(card))
	}

	return FeedPageResponse{Items: items, LastSeenID: output.LastSeenID, HasMore: output.HasMore}
}

func feedCardToResponse(card FeedCard) FeedCardResponse {
	media := make([]FeedMediaResponse, 0, len(card.Media))
	for _, element := range card.Media {
		media = append(media, FeedMediaResponse{
			ID:           element.ID,
			URL:          element.URL,
			ThumbnailURL: element.ThumbnailURL,
			MediaType:    element.MediaType,
			Order:        element.Order,
			DurationMS:   element.DurationMS,
			TaggedUsers:  emptyIfNull(element.TaggedUsers),
		})
	}

	return FeedCardResponse{
		PostID:   card.PostID,
		Position: card.Position,
		Creator: FeedCreatorResponse{
			UserID:     card.Creator.UserID,
			Username:   card.Creator.Username,
			PictureURL: card.Creator.PictureURL,
		},
		Description:       card.Description,
		PostType:          card.PostType,
		PostTags:          emptyIfNull(card.Tags),
		CollabID:          card.CollabID,
		Collab:            FeedCollabResponse{Title: card.CollabTitle, Status: card.CollabStatus},
		CreatedAt:         card.CreatedAt,
		Media:             media,
		CoverThumbnailURL: card.CoverThumbnailURL,
		DurationMS:        card.DurationMS,
		Stats: FeedStatsResponse{
			LikeCount:    card.Stats.LikeCount,
			CommentCount: card.Stats.CommentCount,
			ShareCount:   card.Stats.ShareCount,
		},
		Viewer: FeedViewerResponse{
			Liked:            card.Viewer.Liked,
			Shared:           card.Viewer.Shared,
			FollowingCreator: card.Viewer.FollowingCreator,
		},
	}
}

func feedMediaFromDocuments(documents []FeedMediaDocument) []FeedMedia {
	media := make([]FeedMedia, 0, len(documents))
	for _, document := range documents {
		media = append(media, FeedMedia{
			ID:           document.ID,
			URL:          document.URL,
			ThumbnailURL: document.ThumbnailURL,
			MediaType:    document.MediaType,
			Order:        document.Order,
			DurationMS:   document.DurationMS,
			TaggedUsers:  emptyIfNull(document.TaggedUsers),
		})
	}
	slices.SortStableFunc(media, func(a, b FeedMedia) int { return cmp.Compare(a.Order, b.Order) })

	return media
}

func feedMediaToDocuments(media []FeedMedia) []FeedMediaDocument {
	documents := make([]FeedMediaDocument, 0, len(media))
	for _, element := range media {
		documents = append(documents, FeedMediaDocument{
			ID:           element.ID,
			URL:          element.URL,
			ThumbnailURL: element.ThumbnailURL,
			MediaType:    element.MediaType,
			Order:        element.Order,
			DurationMS:   element.DurationMS,
			TaggedUsers:  emptyIfNull(element.TaggedUsers),
		})
	}

	return documents
}

func feedStatsFromDocument(document FeedStatsDocument) FeedStats {
	return FeedStats{
		LikeCount:    document.LikeCount,
		CommentCount: document.CommentCount,
		ShareCount:   document.ShareCount,
	}
}

func nullIfEmpty(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func emptyIfNull(values []string) []string {
	if values == nil {
		return []string{}
	}
	return values
}
