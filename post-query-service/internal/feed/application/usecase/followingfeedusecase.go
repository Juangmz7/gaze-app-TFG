package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// The following feed (task 51): the Home "Following" tab, the newest posts of the users
// the caller follows. It is a keyset list over the posts read model and shares nothing
// with the recommended feed: no feed_units are read or marked served, no position is
// involved and no prefetch is triggered.

// Drop reasons of the following feed, logged and never answered.
const (
	followingDropReasonCreatorMissing = "creator_missing"
	followingDropReasonCreatorBlocked = "creator_blocked"
)

// FollowingFeedInput asks for one page of the caller's following feed. UserID is the
// caller, from the authenticated context. After is the decoded cursor, nil for the
// first page.
type FollowingFeedInput struct {
	UserID        uuid.UUID
	After         *GridCursor
	Limit         int
	CorrelationID string
}

// validate checks the domain rules every following feed input must meet.
func (i FollowingFeedInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}

	return validLimit(i.Limit)
}

// FollowingPostsQuery reads, newest first ((created_at, post_id) descending), up to
// Limit posts whose creator is one of UserIDs, strictly after After.
type FollowingPostsQuery struct {
	UserIDs []string
	After   *GridCursor
	Limit   int
}

// FollowingFeedConfig holds the following feed's limits.
type FollowingFeedConfig struct {
	// MaxFollows caps the follows a page reads its posts from: when the caller follows
	// more users, only the most recently followed ones are used.
	MaxFollows int
}

// FollowingFeedRepository reads what the following feed is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type FollowingFeedRepository interface {
	feedStateReader
	// FindRecentFollowedUserIDs returns up to limit users viewerID follows, most
	// recently followed first.
	FindRecentFollowedUserIDs(ctx context.Context, viewerID string, limit int) ([]string, error)
	// FindAllBlockRelatedUserIDs returns every user with a block with viewerID, in
	// either direction.
	FindAllBlockRelatedUserIDs(ctx context.Context, viewerID string) ([]string, error)
	// FindProjectedUserIDs returns which of userIDs are in the users read model.
	FindProjectedUserIDs(ctx context.Context, userIDs []string) ([]string, error)
	// FindFollowingPosts reads one page of posts by any of query.UserIDs.
	FindFollowingPosts(ctx context.Context, query FollowingPostsQuery) ([]FeedPost, error)
}

// FollowingFeedPage is one page of the following feed: post views in order, and the
// cursor of the next page, nil on the last.
type FollowingFeedPage struct {
	Items      []FeedCard
	NextCursor *string
}

// FollowingFeedUsecase serves the following feed, newest first, keyset paged.
type FollowingFeedUsecase struct {
	repository FollowingFeedRepository
	config     FollowingFeedConfig
	logger     *slog.Logger
}

// NewFollowingFeed creates a FollowingFeedUsecase.
func NewFollowingFeed(repository FollowingFeedRepository, config FollowingFeedConfig, logger *slog.Logger) *FollowingFeedUsecase {
	return &FollowingFeedUsecase{repository: repository, config: config, logger: logger}
}

// Execute returns one page. The followed ids are read once per request (capped at
// MaxFollows, most recent first); the caller, the users with a block with the caller
// and the users no longer projected are taken out before the page query, so their posts
// never take a place in the page. With nobody left the page is empty and no post is
// read. The page query reads limit+1 posts on the posts index (an index seek after the
// cursor), and the page is hydrated into post views by readFeedState, as GetPost does.
func (u *FollowingFeedUsecase) Execute(ctx context.Context, input FollowingFeedInput) (FollowingFeedPage, error) {
	if err := input.validate(); err != nil {
		return FollowingFeedPage{}, fmt.Errorf("following feed usecase: %w", err)
	}
	if u.config.MaxFollows < 1 {
		return FollowingFeedPage{}, fmt.Errorf("following feed usecase: max follows %d must be positive", u.config.MaxFollows)
	}

	viewerID := input.UserID.String()
	authors, err := u.followedAuthors(ctx, input, viewerID)
	if err != nil {
		return FollowingFeedPage{}, fmt.Errorf("following feed usecase: %w", err)
	}
	if len(authors) == 0 {
		return FollowingFeedPage{Items: []FeedCard{}}, nil
	}

	rows, err := u.repository.FindFollowingPosts(ctx, FollowingPostsQuery{UserIDs: authors, After: input.After, Limit: input.Limit + 1})
	if err != nil {
		return FollowingFeedPage{}, fmt.Errorf("following feed usecase: %w", err)
	}
	var next *string
	if len(rows) > input.Limit {
		rows = rows[:input.Limit]
		last := rows[len(rows)-1]
		next = encodeGridCursorAt(last.CreatedAt, last.PostID)
	}

	items, err := u.hydrate(ctx, input, viewerID, rows)
	if err != nil {
		return FollowingFeedPage{}, fmt.Errorf("following feed usecase: %w", err)
	}

	return FollowingFeedPage{Items: items, NextCursor: next}, nil
}

// followedAuthors reads the users whose posts the page is read from: the caller's
// most recent follows up to MaxFollows, without the caller, the users in a block
// relation with the caller and the users not projected.
func (u *FollowingFeedUsecase) followedAuthors(ctx context.Context, input FollowingFeedInput, viewerID string) ([]string, error) {
	followed, err := u.repository.FindRecentFollowedUserIDs(ctx, viewerID, u.config.MaxFollows+1)
	if err != nil {
		return nil, err
	}
	if len(followed) > u.config.MaxFollows {
		followed = followed[:u.config.MaxFollows]
		u.logger.InfoContext(ctx, "the caller follows more users than the following feed reads: using the most recent follows only",
			"correlation_id", input.CorrelationID, "userId", input.UserID, "maxFollows", u.config.MaxFollows)
	}
	if len(followed) == 0 {
		return nil, nil
	}

	related, err := u.repository.FindAllBlockRelatedUserIDs(ctx, viewerID)
	if err != nil {
		return nil, err
	}
	// skip starts with every user whose posts are hidden from the caller, the caller
	// included, and gains each id taken, so a repeated id is taken once.
	skip := idSet(related)
	skip[viewerID] = struct{}{}
	candidates := make([]string, 0, len(followed))
	for _, userID := range followed {
		if _, skipped := skip[userID]; skipped || userID == "" {
			continue
		}
		skip[userID] = struct{}{}
		candidates = append(candidates, userID)
	}
	if len(candidates) == 0 {
		return nil, nil
	}

	projected, err := u.repository.FindProjectedUserIDs(ctx, candidates)
	if err != nil {
		return nil, err
	}
	present := idSet(projected)
	authors := make([]string, 0, len(candidates))
	for _, userID := range candidates {
		if _, found := present[userID]; found {
			authors = append(authors, userID)
		}
	}

	return authors, nil
}

// hydrate builds the page's post views with one readFeedState for the whole page. A
// post whose creator left the users read model or got a block with the caller since
// the followed ids were read is left out, as GetPost would answer it 404.
func (u *FollowingFeedUsecase) hydrate(ctx context.Context, input FollowingFeedInput, viewerID string, posts []FeedPost) ([]FeedCard, error) {
	if len(posts) == 0 {
		return []FeedCard{}, nil
	}

	postIDs := make([]string, 0, len(posts))
	creatorIDs := make([]string, 0, len(posts))
	var collabIDs []string
	for _, post := range posts {
		postIDs = append(postIDs, post.PostID)
		creatorIDs = append(creatorIDs, post.CreatorID)
		if post.CollabID != nil {
			collabIDs = append(collabIDs, *post.CollabID)
		}
	}
	state, err := readFeedState(ctx, u.repository, viewerID, postIDs, creatorIDs, collabIDs)
	if err != nil {
		return nil, err
	}

	drops := gridDrops{}
	cards := make([]FeedCard, 0, len(posts))
	for _, post := range posts {
		creator, found := state.creators[post.CreatorID]
		if !found {
			drops[followingDropReasonCreatorMissing]++
			continue
		}
		if _, blocked := state.blocked[post.CreatorID]; blocked {
			drops[followingDropReasonCreatorBlocked]++
			continue
		}
		cards = append(cards, FeedCardFromPost(post, creator, state.collabFor(post), state.viewerFor(post)))
	}
	logDrops(ctx, u.logger, "following feed posts left out of a page", drops,
		"correlation_id", input.CorrelationID, "userId", input.UserID)

	return cards, nil
}
