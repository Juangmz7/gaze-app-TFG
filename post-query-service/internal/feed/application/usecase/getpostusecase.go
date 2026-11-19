package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// ErrGetPostNotFound is returned when the post cannot be shown to the caller: it is not
// in the read model (never projected, deleted or banned), its creator is not, or there
// is a block between the caller and its creator. One error for all of them, so the
// answer never reveals a block.
var ErrGetPostNotFound = errors.New("post not found")

// GetPost not-found reasons, logged and never answered.
const (
	getPostReasonPostMissing    = "post_missing"
	getPostReasonCreatorMissing = "creator_missing"
	getPostReasonBlocked        = "blocked"
)

// GetPostInput asks for one post as the caller sees it. UserID comes from the
// authenticated context.
type GetPostInput struct {
	UserID        uuid.UUID
	PostID        uuid.UUID
	CorrelationID string
}

// GetPostHydrationRepository reads the read models a post view is built from: the same
// queries as a feed claim, so both shapes come from one hydration. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type GetPostHydrationRepository interface {
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
	feedStateReader
}

// GetPostUsecase reads one post by id with the caller's viewer state. It touches no
// feed: nothing is marked served and no position is involved.
type GetPostUsecase struct {
	hydration GetPostHydrationRepository
	logger    *slog.Logger
}

// NewGetPost creates a GetPostUsecase.
func NewGetPost(hydration GetPostHydrationRepository, logger *slog.Logger) *GetPostUsecase {
	return &GetPostUsecase{hydration: hydration, logger: logger}
}

// Execute returns the post's card, or ErrGetPostNotFound when the caller may not see it.
func (u *GetPostUsecase) Execute(ctx context.Context, input GetPostInput) (FeedCard, error) {
	if input.UserID == uuid.Nil {
		return FeedCard{}, errors.New("get post usecase: user id is required")
	}
	if input.PostID == uuid.Nil {
		return FeedCard{}, errors.New("get post usecase: post id is required")
	}

	viewerID := input.UserID.String()
	postID := input.PostID.String()
	posts, err := u.hydration.FindPosts(ctx, []string{postID})
	if err != nil {
		return FeedCard{}, fmt.Errorf("get post usecase: %w", err)
	}
	post, found := findFeedPost(posts, postID)
	if !found {
		return FeedCard{}, u.notFound(ctx, input, getPostReasonPostMissing)
	}

	var collabIDs []string
	if post.CollabID != nil {
		collabIDs = []string{*post.CollabID}
	}
	state, err := readFeedState(ctx, u.hydration, viewerID, []string{postID}, []string{post.CreatorID}, collabIDs)
	if err != nil {
		return FeedCard{}, fmt.Errorf("get post usecase: %w", err)
	}

	creator, found := state.creators[post.CreatorID]
	if !found {
		return FeedCard{}, u.notFound(ctx, input, getPostReasonCreatorMissing)
	}
	if _, blocked := state.blocked[post.CreatorID]; blocked {
		return FeedCard{}, u.notFound(ctx, input, getPostReasonBlocked)
	}

	return FeedCardFromPost(post, creator, state.collabFor(post), state.viewerFor(post)), nil
}

// notFound logs why the post is hidden, which the answer never says, and returns
// ErrGetPostNotFound.
func (u *GetPostUsecase) notFound(ctx context.Context, input GetPostInput, reason string) error {
	u.logger.InfoContext(ctx, "post not shown to the caller", "correlation_id", input.CorrelationID, "userId", input.UserID,
		"postId", input.PostID, "reason", reason)

	return fmt.Errorf("get post usecase: %w", ErrGetPostNotFound)
}

// findFeedPost picks postID from what the repository returned, never trusting it to
// return only what was asked for.
func findFeedPost(posts []FeedPost, postID string) (FeedPost, bool) {
	for _, post := range posts {
		if post.PostID == postID {
			return post, true
		}
	}

	return FeedPost{}, false
}
