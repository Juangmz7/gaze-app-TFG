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

// gridSort is the order of both profile grids (task 49), newest first. Its keys, after
// the user_id equality, are exactly those of userGridIndex, so the index walk is the
// order and there is no SORT stage.
var gridSort = bson.D{
	{Key: "created_at", Value: -1},
	{Key: "post_id", Value: -1},
}

// userGridIndex is the index of both grids, on posts (the post context's
// UserPostsIndex) and on post_shares (the share context's UserSharesIndex), hinted so
// the planner never picks another plan.
var userGridIndex = bson.D{
	{Key: "user_id", Value: 1},
	{Key: "created_at", Value: -1},
	{Key: "post_id", Value: -1},
}

// shareProjection is every post_shares field the reposts grid reads.
var shareProjection = bson.D{
	{Key: "post_id", Value: 1},
	{Key: "created_at", Value: 1},
}

// gridShareDocument is the part of a post_shares document the reposts grid reads.
type gridShareDocument struct {
	PostID    string    `bson:"post_id"`
	CreatedAt time.Time `bson:"created_at"`
}

// FindUserPosts reads one page of a user's posts, newest first. With a cursor, "after
// (created_at, post_id)" is split into its two ranges, read in order (see
// readKeysetRanges), so page N costs what page 1 costs.
func (r *HydrationRepository) FindUserPosts(ctx context.Context, query usecase.UserGridQuery) ([]usecase.FeedPost, error) {
	if err := validGridQuery(query); err != nil {
		return nil, fmt.Errorf("find user posts: %w", err)
	}

	documents, err := readKeysetRanges(bson.D{{Key: "user_id", Value: query.UserID}}, gridRanges(query.After), query.Limit,
		func(filter bson.D, remaining int) ([]usecase.FeedPostDocument, error) {
			var page []usecase.FeedPostDocument
			err := r.findGridPage(ctx, postsCollectionName, filter, postProjection, remaining, &page)
			return page, err
		})
	if err != nil {
		return nil, err
	}

	return feedPostsFromDocuments(documents), nil
}

// FindUserShares reads one page of a user's shares, most recently shared first, with
// the same keyset as FindUserPosts on post_shares.
func (r *HydrationRepository) FindUserShares(ctx context.Context, query usecase.UserGridQuery) ([]usecase.UserShare, error) {
	if err := validGridQuery(query); err != nil {
		return nil, fmt.Errorf("find user shares: %w", err)
	}

	documents, err := readKeysetRanges(bson.D{{Key: "user_id", Value: query.UserID}}, gridRanges(query.After), query.Limit,
		func(filter bson.D, remaining int) ([]gridShareDocument, error) {
			var page []gridShareDocument
			err := r.findGridPage(ctx, sharesCollectionName, filter, shareProjection, remaining, &page)
			return page, err
		})
	if err != nil {
		return nil, err
	}

	shares := make([]usecase.UserShare, 0, len(documents))
	for _, document := range documents {
		shares = append(shares, usecase.UserShare{PostID: document.PostID, CreatedAt: document.CreatedAt})
	}

	return shares, nil
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

// gridRanges is "(created_at, post_id) strictly after the cursor" in descending order,
// nearest range first: the same date with a smaller id, then an earlier date. Without
// a cursor, one range with no bound.
func gridRanges(after *usecase.GridCursor) []bson.D {
	if after == nil {
		return []bson.D{nil}
	}

	return []bson.D{
		{
			{Key: "created_at", Value: after.CreatedAt},
			{Key: "post_id", Value: bson.D{{Key: "$lt", Value: after.PostID}}},
		},
		{{Key: "created_at", Value: bson.D{{Key: "$lt", Value: after.CreatedAt}}}},
	}
}

// findGridPage runs one grid page query: projected, sorted along the hinted index,
// limited.
func (r *HydrationRepository) findGridPage(ctx context.Context, collection string, filter, projection bson.D, limit int, out any) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	opts := options.Find().
		SetProjection(projection).
		SetSort(gridSort).
		SetHint(userGridIndex).
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
