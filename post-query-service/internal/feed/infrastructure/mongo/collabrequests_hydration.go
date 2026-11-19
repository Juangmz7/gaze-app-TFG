package mongo

import (
	"context"
	"errors"
	"fmt"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// collabRequestsSort is the requests list's order, oldest request first (so nobody
// waits forever), ties broken by user id. After the collab_id and status equalities it
// is exactly collabRequestsIndex, so the index walk is the order and there is no SORT.
var collabRequestsSort = bson.D{
	{Key: "requested_at", Value: 1},
	{Key: "user_id", Value: 1},
}

// FindCollabOwner returns the owner of collabID, and whether the collab is in the
// post_collabs read model (a deleted collab is not).
func (r *HydrationRepository) FindCollabOwner(ctx context.Context, collabID string) (string, bool, error) {
	if collabID == "" {
		return "", false, errors.New("find collab owner: collab id is required")
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var document struct {
		OwnerUserID string `bson:"owner_user_id"`
	}
	err := r.db.Collection(collabsCollectionName).FindOne(ctx, bson.D{{Key: "collab_id", Value: collabID}},
		options.FindOne().SetProjection(bson.D{{Key: "_id", Value: 0}, {Key: "owner_user_id", Value: 1}}),
	).Decode(&document)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("find owner of collab %s: %w", collabID, err)
	}

	return document.OwnerUserID, true, nil
}

// FindCollabRequests reads one page of a collab's memberships in query.Status, oldest
// request first. With a cursor, "after (requested_at, user_id)" is split into its two
// ranges, read in order (see readKeysetRanges), so page N costs what page 1 costs.
func (r *HydrationRepository) FindCollabRequests(ctx context.Context, query usecase.CollabRequestsQuery) ([]usecase.CollabRequestRow, error) {
	if err := validCollabRequestsQuery(query); err != nil {
		return nil, fmt.Errorf("find collab requests: %w", err)
	}

	base := bson.D{{Key: "collab_id", Value: query.CollabID}, {Key: "status", Value: query.Status}}
	documents, err := readKeysetRanges(base, collabRequestsRanges(query.After), query.Limit,
		func(filter bson.D, remaining int) ([]usecase.CollabRequestDocument, error) {
			return r.findCollabRequestsPage(ctx, filter, remaining)
		})
	if err != nil {
		return nil, fmt.Errorf("find collab requests: %w", err)
	}

	rows := make([]usecase.CollabRequestRow, 0, len(documents))
	for _, document := range documents {
		rows = append(rows, usecase.CollabRequestRowFromDocument(document))
	}

	return rows, nil
}

func validCollabRequestsQuery(query usecase.CollabRequestsQuery) error {
	if query.Limit < 1 {
		return fmt.Errorf("limit %d must be positive", query.Limit)
	}
	if query.CollabID == "" {
		return errors.New("collab id is required")
	}
	if query.Status == "" {
		return errors.New("status is required")
	}

	return nil
}

// collabRequestsRanges is "(requested_at, user_id) strictly after the cursor" in
// ascending order, nearest range first: the same date with a greater user id, then a
// later date. Without a cursor, one range with no bound.
func collabRequestsRanges(after *usecase.CollabRequestsCursor) []bson.D {
	if after == nil {
		return []bson.D{nil}
	}

	return []bson.D{
		{
			{Key: "requested_at", Value: after.RequestedAt},
			{Key: "user_id", Value: bson.D{{Key: "$gt", Value: after.UserID}}},
		},
		{{Key: "requested_at", Value: bson.D{{Key: "$gt", Value: after.RequestedAt}}}},
	}
}

// findCollabRequestsPage runs one range of the requests list: projected, sorted along
// the hinted index, limited.
func (r *HydrationRepository) findCollabRequestsPage(ctx context.Context, filter bson.D, limit int) ([]usecase.CollabRequestDocument, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	opts := options.Find().
		SetProjection(bson.D{
			{Key: "_id", Value: 0},
			{Key: "user_id", Value: 1},
			{Key: "status", Value: 1},
			{Key: "requested_at", Value: 1},
		}).
		SetSort(collabRequestsSort).
		SetHint(collabRequestsIndex).
		SetLimit(int64(limit))
	cursor, err := r.db.Collection(collabMembersCollectionName).Find(ctx, filter, opts)
	if err != nil {
		return nil, fmt.Errorf("find in %s: %w", collabMembersCollectionName, err)
	}
	var page []usecase.CollabRequestDocument
	if err := cursor.All(ctx, &page); err != nil {
		return nil, fmt.Errorf("read from %s: %w", collabMembersCollectionName, err)
	}

	return page, nil
}
