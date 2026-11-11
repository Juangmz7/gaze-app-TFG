// Package usecase implements the feed: presence (heartbeat and disconnect), the
// construction of stored feed units, the feed claim, the per-user lease and the
// asynchronous prefetch with its triggers. One file per goal; every
// exported identifier is prefixed with its goal because they share this directory.
package usecase

import "time"

// Post types and media types as post-command-service publishes them.
const (
	FeedPostTypeBasic  = "BASIC"
	FeedPostTypeCollab = "COLLAB"
	FeedMediaTypeImage = "IMAGE"
	FeedMediaTypeVideo = "VIDEO"
)

// FeedMedia is one media element of a post. URL, ThumbnailURL, MediaType and Order
// always travel together. DurationMS is null for an IMAGE.
type FeedMedia struct {
	ID           string
	URL          string
	ThumbnailURL *string
	MediaType    string
	Order        int
	DurationMS   *int64
	TaggedUsers  []string
}

// FeedStats holds a post's counters.
type FeedStats struct {
	LikeCount    int64
	CommentCount int64
	ShareCount   int64
}

// FeedCreator is the author of a post. Username is the only visible name; there is
// no display name. PictureURL is null until a UserUpdatedEvent sets it.
type FeedCreator struct {
	UserID     string
	Username   string
	PictureURL *string
}

// FeedPost is what the feed reads from the posts read model.
type FeedPost struct {
	PostID      string
	CreatorID   string
	CollabID    *string
	PostType    string
	Description *string
	Tags        []string
	Media       []FeedMedia
	Stats       FeedStats
	CreatedAt   time.Time
}

// FeedCollab is what the feed reads from the post_collabs read model.
type FeedCollab struct {
	CollabID  string
	PostID    string
	Title     *string
	Status    *string
	CreatedAt time.Time
}

// openedAfter says whether c was opened after other. Equal dates fall back to the
// collab id, so the choice never depends on the order MongoDB returned them in.
func (c FeedCollab) openedAfter(other FeedCollab) bool {
	if c.CreatedAt.Equal(other.CreatedAt) {
		return c.CollabID > other.CollabID
	}

	return c.CreatedAt.After(other.CreatedAt)
}

// FeedUnit is one stored feed entry for one user: a denormalized snapshot of a post
// at a reserved position. Its mutable fields are overlaid at claim time.
type FeedUnit struct {
	UserID            string
	PostID            string
	Position          int64
	Creator           FeedCreator
	Description       *string
	PostType          string
	Tags              []string
	CollabID          *string
	Media             []FeedMedia
	CoverThumbnailURL *string
	DurationMS        *int64
	Stats             FeedStats
	PostCreatedAt     time.Time
	IngestedAt        time.Time
	ServedAt          *time.Time
}

// FeedUnitRef is the part of a stored feed unit a claim reads: everything else in
// the card comes from the current read models.
type FeedUnitRef struct {
	PostID        string
	Position      int64
	PostCreatedAt time.Time
}

// FeedViewerState says how the viewer relates to a post and its creator.
type FeedViewerState struct {
	Liked            bool
	Shared           bool
	FollowingCreator bool
}

// FeedCard is one item of a claim response.
type FeedCard struct {
	PostID            string
	Position          int64
	Creator           FeedCreator
	Description       *string
	PostType          string
	Tags              []string
	CollabID          *string
	CollabTitle       *string
	CollabStatus      *string
	CreatedAt         time.Time
	Media             []FeedMedia
	CoverThumbnailURL *string
	DurationMS        *int64
	Stats             FeedStats
	Viewer            FeedViewerState
}
