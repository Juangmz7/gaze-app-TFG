package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// ErrClaimFeedInvalidLimit is returned when the requested limit is not a positive
// number up to the configured maximum.
var ErrClaimFeedInvalidLimit = errors.New("limit must be a positive integer not above the maximum page size")

// ClaimFeedConfig holds the claim's limits (config.FeedConfig).
type ClaimFeedConfig struct {
	DefaultPageSize int
	MaxPageSize     int
	MaxReads        int
}

// ClaimFeedInput asks for the next page. UserID comes from the authenticated context.
// A nil LastSeenID means the first call of a session; a nil Limit means the default
// page size.
type ClaimFeedInput struct {
	UserID        uuid.UUID
	LastSeenID    *uuid.UUID
	Limit         *int
	CorrelationID string
}

// ClaimFeedOutput is one page. LastSeenID is null for an empty page. HasMore says
// whether stored items remain after the page.
type ClaimFeedOutput struct {
	Items      []FeedCard
	LastSeenID *string
	HasMore    bool
}

// ClaimFeedUnitRepository reads and marks stored units. A nil afterPosition reads
// from the start; unservedOnly restricts the read to units never served. Implemented
// by feed/infrastructure/mongo.FeedUnitRepository.
type ClaimFeedUnitRepository interface {
	FindPosition(ctx context.Context, userID, postID string) (int64, bool, error)
	FindAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool, limit int) ([]FeedUnitRef, error)
	HasAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool) (bool, error)
	MarkServed(ctx context.Context, userID string, postIDs []string, servedAt time.Time) error
}

// ClaimFeedHydrationRepository reads the current read models, with one $in query per
// method. Implemented by feed/infrastructure/mongo.HydrationRepository.
type ClaimFeedHydrationRepository interface {
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
	FindCollabs(ctx context.Context, collabIDs, postIDs []string) ([]FeedCollab, error)
	FindLikedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error)
	FindSharedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error)
	FindFollowedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error)
	FindBlockRelatedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error)
}

// ClaimFeedUsecase serves the next page of a user's stored feed.
type ClaimFeedUsecase struct {
	units     ClaimFeedUnitRepository
	hydration ClaimFeedHydrationRepository
	config    ClaimFeedConfig
	logger    *slog.Logger
}

// NewClaimFeed creates a ClaimFeedUsecase.
func NewClaimFeed(units ClaimFeedUnitRepository, hydration ClaimFeedHydrationRepository, config ClaimFeedConfig, logger *slog.Logger) *ClaimFeedUsecase {
	return &ClaimFeedUsecase{units: units, hydration: hydration, config: config, logger: logger}
}

// claimFeedRead is where a claim reads from, and advances as it reads further.
type claimFeedRead struct {
	afterPosition *int64
	unservedOnly  bool
}

// claimFeedDrops counts items left out of a page, by reason.
type claimFeedDrops struct {
	postMissing    int
	creatorMissing int
	blocked        int
}

func (d claimFeedDrops) total() int {
	return d.postMissing + d.creatorMissing + d.blocked
}

// Execute resolves where to read from, fills the page with up to MaxReads reads
// because hydration can drop items, and marks what it consumed as served. A short
// page, even an empty one, is a valid result.
func (u *ClaimFeedUsecase) Execute(ctx context.Context, input ClaimFeedInput) (ClaimFeedOutput, error) {
	if input.UserID == uuid.Nil {
		return ClaimFeedOutput{}, fmt.Errorf("claim feed usecase: user id is required")
	}
	limit, err := u.pageSize(input.Limit)
	if err != nil {
		return ClaimFeedOutput{}, err
	}

	viewerID := input.UserID.String()
	read, err := u.resolveStart(ctx, viewerID, input)
	if err != nil {
		return ClaimFeedOutput{}, err
	}

	cards := make([]FeedCard, 0, limit)
	consumed := make([]string, 0, limit)
	var drops claimFeedDrops
	exhausted := false
	for reads := 0; reads < u.config.MaxReads && len(cards) < limit && !exhausted; reads++ {
		remaining := limit - len(cards)
		refs, err := u.units.FindAfterPosition(ctx, viewerID, read.afterPosition, read.unservedOnly, remaining)
		if err != nil {
			return ClaimFeedOutput{}, fmt.Errorf("claim feed usecase: %w", err)
		}
		exhausted = len(refs) < remaining
		if len(refs) == 0 {
			break
		}

		pageCards, pageDrops, err := u.hydrate(ctx, viewerID, refs)
		if err != nil {
			return ClaimFeedOutput{}, err
		}
		cards = append(cards, pageCards...)
		drops.postMissing += pageDrops.postMissing
		drops.creatorMissing += pageDrops.creatorMissing
		drops.blocked += pageDrops.blocked

		for _, ref := range refs {
			consumed = append(consumed, ref.PostID)
		}
		lastPosition := refs[len(refs)-1].Position
		read.afterPosition = &lastPosition
	}

	hasMore := false
	if !exhausted {
		hasMore, err = u.units.HasAfterPosition(ctx, viewerID, read.afterPosition, read.unservedOnly)
		if err != nil {
			return ClaimFeedOutput{}, fmt.Errorf("claim feed usecase: %w", err)
		}
	}

	// Dropped units are marked too: left unserved, they would be read again by every
	// session start, and enough of them at the head of a feed would use up MaxReads
	// and leave the user with empty pages forever.
	if len(consumed) > 0 {
		if err := u.units.MarkServed(ctx, viewerID, consumed, time.Now().UTC()); err != nil {
			return ClaimFeedOutput{}, fmt.Errorf("claim feed usecase: %w", err)
		}
	}

	if drops.total() > 0 {
		u.logger.WarnContext(ctx, "feed items dropped at claim", "correlation_id", input.CorrelationID, "userId", input.UserID,
			"post_missing", drops.postMissing, "creator_missing", drops.creatorMissing, "blocked", drops.blocked)
	}
	u.logger.InfoContext(ctx, "feed claim served", "correlation_id", input.CorrelationID, "userId", input.UserID,
		"items", len(cards), "dropped", drops.total(), "has_more", hasMore)

	return ClaimFeedOutput{Items: cards, LastSeenID: lastSeenID(cards), HasMore: hasMore}, nil
}

func (u *ClaimFeedUsecase) pageSize(limit *int) (int, error) {
	if limit == nil {
		return u.config.DefaultPageSize, nil
	}
	if *limit <= 0 || *limit > u.config.MaxPageSize {
		return 0, fmt.Errorf("claim feed usecase: %w", ErrClaimFeedInvalidLimit)
	}

	return *limit, nil
}

// resolveStart reads strictly after the cursor, whether or not those units were
// served, so repeating a cursor returns the same page. Without a cursor, or with one
// that no longer resolves, it starts at the lowest unserved position, which is what
// keeps a reopened app from replaying old items.
func (u *ClaimFeedUsecase) resolveStart(ctx context.Context, viewerID string, input ClaimFeedInput) (claimFeedRead, error) {
	if input.LastSeenID == nil {
		return claimFeedRead{unservedOnly: true}, nil
	}

	position, found, err := u.units.FindPosition(ctx, viewerID, input.LastSeenID.String())
	if err != nil {
		return claimFeedRead{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "feed cursor not found, restarting from the lowest unserved position",
			"correlation_id", input.CorrelationID, "userId", input.UserID, "lastSeenId", input.LastSeenID)
		return claimFeedRead{unservedOnly: true}, nil
	}

	return claimFeedRead{afterPosition: &position}, nil
}

// hydrate reads the current read models for one batch of units, with one query per
// collection, and builds the cards that survive, in unit order.
func (u *ClaimFeedUsecase) hydrate(ctx context.Context, viewerID string, refs []FeedUnitRef) ([]FeedCard, claimFeedDrops, error) {
	postIDs := make([]string, 0, len(refs))
	for _, ref := range refs {
		postIDs = append(postIDs, ref.PostID)
	}

	foundPosts, err := u.hydration.FindPosts(ctx, postIDs)
	if err != nil {
		return nil, claimFeedDrops{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	if len(foundPosts) == 0 {
		return nil, claimFeedDrops{postMissing: len(refs)}, nil
	}

	posts := make(map[string]FeedPost, len(foundPosts))
	creatorIDs := make([]string, 0, len(foundPosts))
	collabIDs := make([]string, 0, len(foundPosts))
	for _, post := range foundPosts {
		posts[post.PostID] = post
		creatorIDs = append(creatorIDs, post.CreatorID)
		if post.CollabID != nil {
			collabIDs = append(collabIDs, *post.CollabID)
		}
	}

	state, err := u.readClaimState(ctx, viewerID, postIDs, creatorIDs, collabIDs)
	if err != nil {
		return nil, claimFeedDrops{}, err
	}

	cards := make([]FeedCard, 0, len(refs))
	var drops claimFeedDrops
	for _, ref := range refs {
		post, found := posts[ref.PostID]
		if !found {
			drops.postMissing++
			continue
		}
		creator, found := state.creators[post.CreatorID]
		if !found {
			drops.creatorMissing++
			continue
		}
		if _, blocked := state.blocked[post.CreatorID]; blocked {
			drops.blocked++
			continue
		}
		cards = append(cards, FeedCardFromUnit(ref, post, creator, state.collabFor(post), state.viewerFor(post)))
	}

	return cards, drops, nil
}

// claimFeedState is the read-model state of one batch of units.
type claimFeedState struct {
	creators      map[string]FeedCreator
	collabsByID   map[string]FeedCollab
	collabsByPost map[string]FeedCollab
	liked         map[string]struct{}
	shared        map[string]struct{}
	followed      map[string]struct{}
	blocked       map[string]struct{}
}

// collabFor picks the most recently opened of the collabs attached to the post and
// the one the post names, because that is the one post-command-service holds: opening
// a collab on a post overwrites its collabId, without any event reaching
// posts.collab_id, so posts.collab_id can name an older collab or one that is gone.
func (s claimFeedState) collabFor(post FeedPost) *FeedCollab {
	collab, found := s.collabsByPost[post.PostID]
	if post.CollabID != nil {
		named, namedFound := s.collabsByID[*post.CollabID]
		if namedFound && (!found || named.openedAfter(collab)) {
			collab, found = named, true
		}
	}
	if !found {
		return nil
	}

	return &collab
}

func (s claimFeedState) viewerFor(post FeedPost) FeedViewerState {
	_, liked := s.liked[post.PostID]
	_, shared := s.shared[post.PostID]
	_, following := s.followed[post.CreatorID]

	return FeedViewerState{Liked: liked, Shared: shared, FollowingCreator: following}
}

func (u *ClaimFeedUsecase) readClaimState(ctx context.Context, viewerID string, postIDs, creatorIDs, collabIDs []string) (claimFeedState, error) {
	creators, err := u.hydration.FindCreators(ctx, creatorIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	blocked, err := u.hydration.FindBlockRelatedUserIDs(ctx, viewerID, creatorIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	collabs, err := u.hydration.FindCollabs(ctx, collabIDs, postIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	liked, err := u.hydration.FindLikedPostIDs(ctx, viewerID, postIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	shared, err := u.hydration.FindSharedPostIDs(ctx, viewerID, postIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}
	followed, err := u.hydration.FindFollowedUserIDs(ctx, viewerID, creatorIDs)
	if err != nil {
		return claimFeedState{}, fmt.Errorf("claim feed usecase: %w", err)
	}

	state := claimFeedState{
		creators:      make(map[string]FeedCreator, len(creators)),
		collabsByID:   make(map[string]FeedCollab, len(collabs)),
		collabsByPost: make(map[string]FeedCollab, len(collabs)),
		liked:         idSet(liked),
		shared:        idSet(shared),
		followed:      idSet(followed),
		blocked:       idSet(blocked),
	}
	for _, creator := range creators {
		state.creators[creator.UserID] = creator
	}
	for _, collab := range collabs {
		state.collabsByID[collab.CollabID] = collab
		if current, found := state.collabsByPost[collab.PostID]; !found || collab.openedAfter(current) {
			state.collabsByPost[collab.PostID] = collab
		}
	}

	return state, nil
}

func idSet(ids []string) map[string]struct{} {
	set := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		set[id] = struct{}{}
	}
	return set
}

func lastSeenID(cards []FeedCard) *string {
	if len(cards) == 0 {
		return nil
	}
	postID := cards[len(cards)-1].PostID
	return &postID
}
