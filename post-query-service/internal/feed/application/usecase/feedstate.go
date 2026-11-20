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
	FindCollabMemberships(ctx context.Context, viewerID string, collabIDs []string) ([]FeedCollabMembership, error)
	CountPendingCollabRequests(ctx context.Context, collabIDs []string) (map[string]int64, error)
}

// collabMemberStatusAccepted is the membership status of an accepted collab member.
const collabMemberStatusAccepted = "ACCEPTED"

// feedState is the read-model state around a batch of posts, as one viewer sees it.
type feedState struct {
	collabIndex
	viewerID string
	creators map[string]FeedCreator
	liked    map[string]struct{}
	shared   map[string]struct{}
	followed map[string]struct{}
	blocked  map[string]struct{}
	// memberships is the viewer's collab_members status by collab id.
	memberships map[string]string
	// pendingCounts is the PENDING requests count by collab id, read only for the
	// collabs the viewer owns.
	pendingCounts map[string]int64
}

// readFeedState reads the creators, collabs, viewer state and blocks of a batch of
// posts, with one query per collection whatever the batch size. The collab join
// request state (task 52) costs two more: the viewer's memberships of every collab
// read (one $in), and the pending counts of the ones the viewer owns (one $in, not
// run when the viewer owns none of them), so nobody else's count is ever read.
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
	memberships, pendingCounts, err := readCollabRequestState(ctx, reader, viewerID, collabs)
	if err != nil {
		return feedState{}, fmt.Errorf("read feed state: %w", err)
	}

	state := feedState{
		collabIndex:   indexCollabs(collabs),
		viewerID:      viewerID,
		creators:      make(map[string]FeedCreator, len(creators)),
		liked:         idSet(liked),
		shared:        idSet(shared),
		followed:      idSet(followed),
		blocked:       idSet(blocked),
		memberships:   memberships,
		pendingCounts: pendingCounts,
	}
	for _, creator := range creators {
		state.creators[creator.UserID] = creator
	}

	return state, nil
}

// readCollabRequestState reads the viewer's membership status in each collab, and the
// PENDING requests count of each collab the viewer owns.
func readCollabRequestState(ctx context.Context, reader feedStateReader, viewerID string, collabs []FeedCollab) (map[string]string, map[string]int64, error) {
	if len(collabs) == 0 {
		return map[string]string{}, map[string]int64{}, nil
	}

	collabIDs := make([]string, 0, len(collabs))
	var owned []string
	for _, collab := range collabs {
		collabIDs = append(collabIDs, collab.CollabID)
		if collab.OwnerUserID != "" && collab.OwnerUserID == viewerID {
			owned = append(owned, collab.CollabID)
		}
	}

	found, err := reader.FindCollabMemberships(ctx, viewerID, collabIDs)
	if err != nil {
		return nil, nil, err
	}
	memberships := make(map[string]string, len(found))
	for _, membership := range found {
		memberships[membership.CollabID] = membership.Status
	}

	pendingCounts := map[string]int64{}
	if len(owned) > 0 {
		pendingCounts, err = reader.CountPendingCollabRequests(ctx, owned)
		if err != nil {
			return nil, nil, err
		}
	}

	return memberships, pendingCounts, nil
}

// collabFor resolves the post's collab (collabIndex.collabFor) as the viewer sees it:
// with its PENDING requests count when the viewer owns it (0 when none is pending), and
// a null count for everyone else; with its join instructions when the viewer may read
// them (canReadJoinInstructions) and the collab has some, null otherwise.
func (s feedState) collabFor(post FeedPost) *FeedCollab {
	collab := s.collabIndex.collabFor(post)
	if collab == nil {
		return nil
	}

	if s.ownsCollab(*collab) {
		count := s.pendingCounts[collab.CollabID]
		collab.PendingRequestCount = &count
	}
	if s.canReadJoinInstructions(*collab) {
		collab.JoinInstructions = nullIfEmpty(collab.storedJoinInstructions)
	}

	return collab
}

// ownsCollab says whether the viewer is the collab's owner (its creator).
func (s feedState) ownsCollab(collab FeedCollab) bool {
	return s.viewerID != "" && collab.OwnerUserID == s.viewerID
}

// canReadJoinInstructions says whether the viewer may read the collab's join
// instructions (task 61): its owner, or a member whose status is ACCEPTED. An ADMIN
// member is covered by the second rule, since post-command-service only gives the ADMIN
// role to an ACCEPTED member; the creator's own ADMIN row is never projected (no event
// carries it), hence the owner rule. PENDING, REJECTED, DELETED, LEFT, BANNED and no
// membership at all read nothing.
func (s feedState) canReadJoinInstructions(collab FeedCollab) bool {
	if s.ownsCollab(collab) {
		return true
	}

	return s.viewerID != "" && s.memberships[collab.CollabID] == collabMemberStatusAccepted
}

// collabIndex holds the collabs read for a batch of posts (FindCollabs), indexed for
// collabFor: a feed card, a post view and a grid item resolve a post's collab the same
// way.
type collabIndex struct {
	collabsByID   map[string]FeedCollab
	collabsByPost map[string]FeedCollab
}

// indexCollabs indexes collabs by id, and by post keeping the most recently opened.
func indexCollabs(collabs []FeedCollab) collabIndex {
	index := collabIndex{
		collabsByID:   make(map[string]FeedCollab, len(collabs)),
		collabsByPost: make(map[string]FeedCollab, len(collabs)),
	}
	for _, collab := range collabs {
		index.collabsByID[collab.CollabID] = collab
		if current, found := index.collabsByPost[collab.PostID]; !found || collab.openedAfter(current) {
			index.collabsByPost[collab.PostID] = collab
		}
	}

	return index
}

// collabFor picks the most recently opened of the collabs attached to the post and
// the one the post names, because that is the one post-command-service holds: opening
// a collab on a post overwrites its collabId, without any event reaching
// posts.collab_id, so posts.collab_id can name an older collab or one that is gone.
func (s collabIndex) collabFor(post FeedPost) *FeedCollab {
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
	viewer := FeedViewerState{Liked: liked, Shared: shared, FollowingCreator: following}

	if collab := s.collabIndex.collabFor(post); collab != nil {
		if status, found := s.memberships[collab.CollabID]; found {
			viewer.CollabRequestStatus = &status
		}
	}

	return viewer
}

func idSet(ids []string) map[string]struct{} {
	set := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		set[id] = struct{}{}
	}
	return set
}
