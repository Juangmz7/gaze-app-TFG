package mongo

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// Sort-time fields of the keyset lists of posts: a post's or a share's or a like's
// created_at, and a viewing history row's last_viewed_at.
const (
	createdAtField    = "created_at"
	lastViewedAtField = "last_viewed_at"
)

// viewHistoryCollectionName is the viewing history the view context projects (task 50).
const viewHistoryCollectionName = "view_history"

// userListSort is the order of every per-user list of posts (the profile grids of task
// 49 and the own tabs of task 50), most recent first, on its time field. Its keys,
// after the user_id equality, are exactly those of userListIndex, so the index walk is
// the order and there is no SORT stage.
func userListSort(timeField string) bson.D {
	return bson.D{
		{Key: timeField, Value: -1},
		{Key: "post_id", Value: -1},
	}
}

// userListIndex is the index of a per-user list, hinted so the planner never picks
// another plan: UserPostsIndex on posts, UserSharesIndex on post_shares,
// UserLikesIndex on post_likes (created_at) and UserHistoryIndex on view_history
// (last_viewed_at).
func userListIndex(timeField string) bson.D {
	return append(bson.D{{Key: "user_id", Value: 1}}, userListSort(timeField)...)
}

// postRefDocument is the part of a share, like or viewing history document a ref list
// reads; At is decoded from the list's time field (see postRefProjection).
type postRefDocument struct {
	PostID string    `bson:"post_id"`
	At     time.Time `bson:"at"`
}

// postRefProjection reads post_id and the list's time field, renamed at.
func postRefProjection(timeField string) bson.D {
	return bson.D{
		{Key: "_id", Value: 0},
		{Key: "post_id", Value: 1},
		{Key: "at", Value: "$" + timeField},
	}
}

// FindUserPosts reads one page of a user's posts, newest first. With a cursor, "after
// (created_at, post_id)" is split into its two ranges, read in order (see
// readKeysetRanges), so page N costs what page 1 costs.
func (r *HydrationRepository) FindUserPosts(ctx context.Context, query usecase.UserGridQuery) ([]usecase.FeedPost, error) {
	if err := validGridQuery(query); err != nil {
		return nil, fmt.Errorf("find user posts: %w", err)
	}

	documents, err := readKeysetRanges(bson.D{{Key: "user_id", Value: query.UserID}}, gridRanges(createdAtField, query.After), query.Limit,
		func(filter bson.D, remaining int) ([]usecase.FeedPostDocument, error) {
			var page []usecase.FeedPostDocument
			err := r.findGridPage(ctx, postsCollectionName, createdAtField, filter, postProjection, remaining, &page)
			return page, err
		})
	if err != nil {
		return nil, err
	}

	return feedPostsFromDocuments(documents), nil
}

// FindUserShares reads one page of a user's shares, most recently shared first, with
// the same keyset as FindUserPosts on post_shares.
func (r *HydrationRepository) FindUserShares(ctx context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	refs, err := r.findPostRefs(ctx, sharesCollectionName, createdAtField, query)
	if err != nil {
		return nil, fmt.Errorf("find user shares: %w", err)
	}
	return refs, nil
}

// FindUserLikes reads one page of a user's likes, most recently liked first, with the
// same keyset on post_likes.
func (r *HydrationRepository) FindUserLikes(ctx context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	refs, err := r.findPostRefs(ctx, likesCollectionName, createdAtField, query)
	if err != nil {
		return nil, fmt.Errorf("find user likes: %w", err)
	}
	return refs, nil
}

// FindUserViews reads one page of a user's viewing history, most recently viewed
// first, with the same keyset on view_history's last_viewed_at.
func (r *HydrationRepository) FindUserViews(ctx context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	refs, err := r.findPostRefs(ctx, viewHistoryCollectionName, lastViewedAtField, query)
	if err != nil {
		return nil, fmt.Errorf("find user views: %w", err)
	}
	return refs, nil
}

// findPostRefs reads one page of a ref list: rows of query.UserID in collection,
// (timeField, post_id) descending, strictly after query.After.
func (r *HydrationRepository) findPostRefs(ctx context.Context, collection, timeField string, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	if err := validGridQuery(query); err != nil {
		return nil, err
	}

	documents, err := readKeysetRanges(bson.D{{Key: "user_id", Value: query.UserID}}, gridRanges(timeField, query.After), query.Limit,
		func(filter bson.D, remaining int) ([]postRefDocument, error) {
			var page []postRefDocument
			err := r.findGridPage(ctx, collection, timeField, filter, postRefProjection(timeField), remaining, &page)
			return page, err
		})
	if err != nil {
		return nil, err
	}

	refs := make([]usecase.PostRef, 0, len(documents))
	for _, document := range documents {
		refs = append(refs, usecase.PostRef{PostID: document.PostID, At: document.At})
	}

	return refs, nil
}

func validGridQuery(query usecase.UserGridQuery) error {
	if query.Limit < 1 {
		return fmt.Errorf("limit %d must be positive", query.Limit)
	}
	if query.UserID == "" {
		return errors.New("user id is required")
	}

	return nil
}

// gridRanges is "(timeField, post_id) strictly after the cursor" in descending order,
// nearest range first: the same time with a smaller id, then an earlier time. Without
// a cursor, one range with no bound.
func gridRanges(timeField string, after *usecase.GridCursor) []bson.D {
	if after == nil {
		return []bson.D{nil}
	}

	return []bson.D{
		{
			{Key: timeField, Value: after.CreatedAt},
			{Key: "post_id", Value: bson.D{{Key: "$lt", Value: after.PostID}}},
		},
		{{Key: timeField, Value: bson.D{{Key: "$lt", Value: after.CreatedAt}}}},
	}
}

// findGridPage runs one page query of a per-user list sorted on timeField: projected,
// sorted along the hinted index, limited.
func (r *HydrationRepository) findGridPage(ctx context.Context, collection, timeField string, filter, projection bson.D, limit int, out any) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	opts := options.Find().
		SetProjection(projection).
		SetSort(userListSort(timeField)).
		SetHint(userListIndex(timeField)).
		SetLimit(int64(limit))
	cursor, err := r.db.Collection(collection).Find(ctx, filter, opts)
	if err != nil {
		return fmt.Errorf("find in %s: %w", collection, err)
	}
	if err := cursor.All(ctx, out); err != nil {
		return fmt.Errorf("read from %s: %w", collection, err)
	}

	return nil
}
