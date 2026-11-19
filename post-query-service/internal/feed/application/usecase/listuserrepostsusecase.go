package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"
)

// userRepostsGrid names the reposts grid in logs.
const userRepostsGrid = "reposts"

// UserRepostsMaxReads bounds the share reads of one reposts page. A share outlives its
// post (a deleted post leaves its post_shares documents), so a read can come back with
// fewer visible posts than asked for; the page reads on after the last share until it is
// full, at most this many times, then answers a short page whose cursor resumes after
// the last share read.
const UserRepostsMaxReads = 3

// UserShare is one share of the reposts grid: which post, and when it was shared.
type UserShare struct {
	PostID    string
	CreatedAt time.Time
}

// ListUserRepostsRepository reads what the reposts grid is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListUserRepostsRepository interface {
	gridReader
	FindUserShares(ctx context.Context, query UserGridQuery) ([]UserShare, error)
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
}

// ListUserRepostsUsecase serves the posts a user shared, most recently shared first,
// keyset paged on the share.
type ListUserRepostsUsecase struct {
	repository ListUserRepostsRepository
	logger     *slog.Logger
}

// NewListUserReposts creates a ListUserRepostsUsecase.
func NewListUserReposts(repository ListUserRepostsRepository, logger *slog.Logger) *ListUserRepostsUsecase {
	return &ListUserRepostsUsecase{repository: repository, logger: logger}
}

// Execute returns one page, or ErrGridUserNotFound when the caller may not see the
// user. A shared post is left out when it no longer exists, when its creator is not
// projected, or when its creator has a block with the caller: the same posts GetPost
// answers 404 for. Each read is three queries (shares, then their posts and creators
// with one $in each, never one by one), plus the user, the caller's blocks and the
// page's collabs.
func (u *ListUserRepostsUsecase) Execute(ctx context.Context, input UserGridInput) (GridPage, error) {
	if err := input.validate(); err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}

	targetID := input.TargetUserID.String()
	blocked, reason, err := findVisibleTarget(ctx, u.repository, input.UserID.String(), targetID)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}
	if reason != "" {
		logGridNotFound(ctx, u.logger, userRepostsGrid, input, reason)
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", ErrGridUserNotFound)
	}

	drops := gridDrops{}
	posts := make([]FeedPost, 0, input.Limit)
	after := input.After
	var next *string
	for read := 1; read <= UserRepostsMaxReads; read++ {
		missing := input.Limit - len(posts)
		shares, err := u.repository.FindUserShares(ctx, UserGridQuery{UserID: targetID, After: after, Limit: missing + 1})
		if err != nil {
			return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
		}
		more := len(shares) > missing
		consumed := shares[:min(missing, len(shares))]

		visible, err := u.visiblePosts(ctx, consumed, blocked, drops)
		if err != nil {
			return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
		}
		posts = append(posts, visible...)
		if len(consumed) > 0 {
			last := consumed[len(consumed)-1]
			after = &GridCursor{CreatedAt: last.CreatedAt, PostID: last.PostID}
		}
		if !more {
			break
		}
		if len(posts) == input.Limit || read == UserRepostsMaxReads {
			next = encodeGridCursorAt(after.CreatedAt, after.PostID)
			break
		}
	}

	items, err := gridItems(ctx, u.repository, posts, drops)
	if err != nil {
		return GridPage{}, fmt.Errorf("list user reposts usecase: %w", err)
	}
	logGridDrops(ctx, u.logger, userRepostsGrid, input, drops)

	return GridPage{Items: items, NextCursor: next}, nil
}

// visiblePosts reads the posts of shares and their creators with one $in each, and
// returns, in share order, those the caller may see.
func (u *ListUserRepostsUsecase) visiblePosts(ctx context.Context, shares []UserShare, blocked map[string]struct{}, drops gridDrops) ([]FeedPost, error) {
	if len(shares) == 0 {
		return nil, nil
	}

	postIDs := make([]string, 0, len(shares))
	for _, share := range shares {
		postIDs = append(postIDs, share.PostID)
	}
	found, err := u.repository.FindPosts(ctx, postIDs)
	if err != nil {
		return nil, err
	}
	posts := make(map[string]FeedPost, len(found))
	creatorIDs := make([]string, 0, len(found))
	for _, post := range found {
		posts[post.PostID] = post
		creatorIDs = append(creatorIDs, post.CreatorID)
	}
	creators, err := u.repository.FindCreators(ctx, creatorIDs)
	if err != nil {
		return nil, err
	}
	projected := make(map[string]struct{}, len(creators))
	for _, creator := range creators {
		projected[creator.UserID] = struct{}{}
	}

	visible := make([]FeedPost, 0, len(shares))
	for _, share := range shares {
		post, found := posts[share.PostID]
		if !found {
			drops[gridDropReasonPostMissing]++
			continue
		}
		if _, isBlocked := blocked[post.CreatorID]; isBlocked {
			drops[gridDropReasonCreatorBlocked]++
			continue
		}
		if _, isProjected := projected[post.CreatorID]; !isProjected {
			drops[gridDropReasonCreatorMissing]++
			continue
		}
		visible = append(visible, post)
	}

	return visible, nil
}
