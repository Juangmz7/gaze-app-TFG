package mongo

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// followingAuthorsPerQuery bounds the followed ids of one page query of the following
// feed. A $in on user_id with a sort on (created_at, post_id) is planned as one index
// scan per id merged in sort order (SORT_MERGE, no blocking SORT), but only up to
// MongoDB's internalQueryMaxScansToExplode, 200 by default: with more ids the planner
// scans every post of every id and sorts them in memory. So the ids are split into
// queries of at most this many, each a merge of index seeks, and their pages merged
// (measured with 250 ids in one query: IXSCAN + SORT; split in two: SORT_MERGE). The
// tie range (created_at = C, post_id < I) only matches the posts of the cursor's exact
// millisecond, so the planner may sort those few rows instead; that never grows with
// the page number.
const followingAuthorsPerQuery = 200

// recentFollowsSort is the order of a follower's follows, most recent first: the keys
// of follow.RecentFollowsIndex after follower_user_id.
var recentFollowsSort = bson.D{
	{Key: "created_at", Value: -1},
	{Key: "followed_user_id", Value: -1},
}

// FindRecentFollowedUserIDs returns up to limit users viewerID follows, most recently
// followed first. The read is covered by follow.RecentFollowsIndex: only index keys are
// read, no document.
func (r *HydrationRepository) FindRecentFollowedUserIDs(ctx context.Context, viewerID string, limit int) ([]string, error) {
	if viewerID == "" {
		return nil, errors.New("find recent followed user ids: viewer id is required")
	}
	if limit < 1 {
		return nil, fmt.Errorf("find recent followed user ids: limit %d must be positive", limit)
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	opts := options.Find().
		SetProjection(bson.D{{Key: "_id", Value: 0}, {Key: "followed_user_id", Value: 1}}).
		SetSort(recentFollowsSort).
		SetHint(append(bson.D{{Key: "follower_user_id", Value: 1}}, recentFollowsSort...)).
		SetLimit(int64(limit))
	cursor, err := r.db.Collection(followsCollectionName).Find(ctx, bson.D{{Key: "follower_user_id", Value: viewerID}}, opts)
	if err != nil {
		return nil, fmt.Errorf("find in %s: %w", followsCollectionName, err)
	}
	var documents []struct {
		FollowedUserID string `bson:"followed_user_id"`
	}
	if err := cursor.All(ctx, &documents); err != nil {
		return nil, fmt.Errorf("read from %s: %w", followsCollectionName, err)
	}

	followed := make([]string, 0, len(documents))
	for _, document := range documents {
		followed = append(followed, document.FollowedUserID)
	}

	return followed, nil
}

// FindProjectedUserIDs returns which of userIDs are in the users read model. It reads
// only user_id, which the unique user_id index holds, so the read is covered.
func (r *HydrationRepository) FindProjectedUserIDs(ctx context.Context, userIDs []string) ([]string, error) {
	if len(userIDs) == 0 {
		return nil, nil
	}

	var documents []struct {
		UserID string `bson:"user_id"`
	}
	projection := bson.D{{Key: "_id", Value: 0}, {Key: "user_id", Value: 1}}
	if err := r.find(ctx, usersCollectionName, bson.D{{Key: "user_id", Value: in(userIDs)}}, projection, &documents); err != nil {
		return nil, err
	}

	projected := make([]string, 0, len(documents))
	for _, document := range documents {
		projected = append(projected, document.UserID)
	}

	return projected, nil
}

// FindFollowingPosts reads one page of the posts of any of query.UserIDs, newest first,
// on post.UserPostsIndex. The ids are read in groups of followingAuthorsPerQuery; each
// group runs the two keyset ranges of the profile grids (readKeysetRanges) for up to
// query.Limit posts, and the groups' pages are merged in the same order and cut to
// query.Limit. Every group query is a merge of index seeks that start at the cursor.
func (r *HydrationRepository) FindFollowingPosts(ctx context.Context, query usecase.FollowingPostsQuery) ([]usecase.FeedPost, error) {
	if query.Limit < 1 {
		return nil, fmt.Errorf("find following posts: limit %d must be positive", query.Limit)
	}
	if len(query.UserIDs) == 0 {
		return nil, nil
	}

	var documents []usecase.FeedPostDocument
	for authors := range slices.Chunk(query.UserIDs, followingAuthorsPerQuery) {
		page, err := readKeysetRanges(bson.D{{Key: "user_id", Value: in(authors)}}, gridRanges(createdAtField, query.After), query.Limit,
			func(filter bson.D, remaining int) ([]usecase.FeedPostDocument, error) {
				var rows []usecase.FeedPostDocument
				err := r.findGridPage(ctx, postsCollectionName, createdAtField, filter, postProjection, remaining, &rows)
				return rows, err
			})
		if err != nil {
			return nil, fmt.Errorf("find following posts: %w", err)
		}
		documents = append(documents, page...)
	}

	// Each group's page is already in order; the merged page is the first query.Limit
	// of their union in the same order: (created_at, post_id) descending.
	slices.SortFunc(documents, func(a, b usecase.FeedPostDocument) int {
		return cmp.Or(b.CreatedAt.Compare(a.CreatedAt), strings.Compare(b.PostID, a.PostID))
	})
	if len(documents) > query.Limit {
		documents = documents[:query.Limit]
	}

	return feedPostsFromDocuments(documents), nil
}
