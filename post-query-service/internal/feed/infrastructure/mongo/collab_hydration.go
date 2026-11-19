package mongo

import (
	"context"
	"fmt"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// collabMembersCollectionName is the collab membership read model the collab context
// projects from the join request and member events (task 52).
const collabMembersCollectionName = "collab_members"

// collabPendingStatus is the membership status of a join request awaiting the owner.
const collabPendingStatus = "PENDING"

// collabRequestsIndex is collab.MemberRequestsIndex: equality on collab_id and status,
// then requested_at and user_id. It serves the pending counts below and the requests
// list (collabrequests_hydration.go), hinted by its keys so the planner never picks the
// unique (collab_id, user_id) index and sorts.
var collabRequestsIndex = bson.D{
	{Key: "collab_id", Value: 1},
	{Key: "status", Value: 1},
	{Key: "requested_at", Value: 1},
	{Key: "user_id", Value: 1},
}

// FindCollabMemberships returns the viewer's membership of each of collabIDs that has
// one: one $in on the unique (collab_id, user_id) index.
func (r *HydrationRepository) FindCollabMemberships(ctx context.Context, viewerID string, collabIDs []string) ([]usecase.FeedCollabMembership, error) {
	if len(collabIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{{Key: "collab_id", Value: in(collabIDs)}, {Key: "user_id", Value: viewerID}}
	var documents []usecase.FeedCollabMemberDocument
	err := r.find(ctx, collabMembersCollectionName, filter, bson.D{
		{Key: "_id", Value: 0},
		{Key: "collab_id", Value: 1},
		{Key: "status", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	memberships := make([]usecase.FeedCollabMembership, 0, len(documents))
	for _, document := range documents {
		memberships = append(memberships, usecase.FeedCollabMembershipFromDocument(document))
	}

	return memberships, nil
}

// CountPendingCollabRequests counts the PENDING memberships of each of collabIDs with
// one aggregation, matched on the leading (collab_id, status) keys of
// collabRequestsIndex, so it reads index keys only. A collab with none is absent from
// the result.
func (r *HydrationRepository) CountPendingCollabRequests(ctx context.Context, collabIDs []string) (map[string]int64, error) {
	if len(collabIDs) == 0 {
		return map[string]int64{}, nil
	}

	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	pipeline := mongo.Pipeline{
		{{Key: "$match", Value: bson.D{
			{Key: "collab_id", Value: in(collabIDs)},
			{Key: "status", Value: collabPendingStatus},
		}}},
		{{Key: "$group", Value: bson.D{
			{Key: "_id", Value: "$collab_id"},
			{Key: "count", Value: bson.D{{Key: "$sum", Value: 1}}},
		}}},
	}
	cursor, err := r.db.Collection(collabMembersCollectionName).Aggregate(ctx, pipeline, options.Aggregate().SetHint(collabRequestsIndex))
	if err != nil {
		return nil, fmt.Errorf("count pending requests in %s: %w", collabMembersCollectionName, err)
	}
	var documents []usecase.FeedCollabPendingCountDocument
	if err := cursor.All(ctx, &documents); err != nil {
		return nil, fmt.Errorf("read pending requests counts from %s: %w", collabMembersCollectionName, err)
	}

	counts := make(map[string]int64, len(documents))
	for _, document := range documents {
		counts[document.CollabID] = document.Count
	}

	return counts, nil
}
