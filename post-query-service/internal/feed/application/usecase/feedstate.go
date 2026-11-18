package usecase

import (
	"context"
	"fmt"
)

// feedStateReader is what feedState is read from: one projected $in query per method.
// ClaimFeedHydrationRepository and GetPostHydrationRepository both satisfy it, so a feed
// card and a post view are hydrated by the same queries.
type feedStateReader interface {
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
	FindCollabs(ctx context.Context, collabIDs, postIDs []string) ([]FeedCollab, error)
	FindLikedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error)
	FindSharedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error)
	FindFollowedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error)
	FindBlockRelatedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error)
}

// feedState is the read-model state around a batch of posts, as one viewer sees it.
type feedState struct {
	creators      map[string]FeedCreator
	collabsByID   map[string]FeedCollab
	collabsByPost map[string]FeedCollab
	liked         map[string]struct{}
	shared        map[string]struct{}
	followed      map[string]struct{}
	blocked       map[string]struct{}
}

// readFeedState reads the creators, collabs, viewer state and blocks of a batch of
// posts, with one query per collection whatever the batch size.
func readFeedState(ctx context.Context, reader feedStateReader, viewerID string, postIDs, creatorIDs, collabIDs []string) (feedState, error) {
	creators, err := reader.FindCreators(ctx, creatorIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}
	blocked, err := reader.FindBlockRelatedUserIDs(ctx, viewerID, creatorIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}
	collabs, err := reader.FindCollabs(ctx, collabIDs, postIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}
	liked, err := reader.FindLikedPostIDs(ctx, viewerID, postIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}
	shared, err := reader.FindSharedPostIDs(ctx, viewerID, postIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}
	followed, err := reader.FindFollowedUserIDs(ctx, viewerID, creatorIDs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}

	state := feedState{
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

// collabFor picks the most recently opened of the collabs attached to the post and
// the one the post names, because that is the one post-command-service holds: opening
// a collab on a post overwrites its collabId, without any event reaching
// posts.collab_id, so posts.collab_id can name an older collab or one that is gone.
func (s feedState) collabFor(post FeedPost) *FeedCollab {
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

func (s feedState) viewerFor(post FeedPost) FeedViewerState {
	_, liked := s.liked[post.PostID]
	_, shared := s.shared[post.PostID]
	_, following := s.followed[post.CreatorID]

	return FeedViewerState{Liked: liked, Shared: shared, FollowingCreator: following}
}

func idSet(ids []string) map[string]struct{} {
	set := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		set[id] = struct{}{}
	}
	return set
}
