package mongo

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

// MembersCollectionName is the MongoDB collection of the collab memberships projected
// from the join request and member events: one document per (collab_id, user_id).
const MembersCollectionName = "collab_members"

// MemberRequestsIndex is the name of the collab_members index that serves the pending
// counts of the feed and the requests list of GET
// /api/content/collabs/{collabId}/requests (task 52): equality on collab_id and status,
// then requested_at and user_id ascending, the list's sort, so every keyset page is an
// index seek and a count reads index keys only.
const MemberRequestsIndex = "collab_id_1_status_1_requested_at_1_user_id_1"

// memberDocument is the persisted shape of a membership. updated_at is the occurredAt
// of the last event applied, which orders the events (they carry no version); version
// counts the applied changes and gates the update against a concurrent writer.
type memberDocument struct {
	CollabID    string    `bson:"collab_id"`
	UserID      string    `bson:"user_id"`
	Status      string    `bson:"status"`
	Role        string    `bson:"role"`
	RequestedAt time.Time `bson:"requested_at"`
	UpdatedAt   time.Time `bson:"updated_at"`
	Version     int64     `bson:"version"`
}

// MemberRepository implements usecase.ApplyCollabMemberRepository against MongoDB.
type MemberRepository struct {
	collection *mongo.Collection
}

// NewMemberRepository creates a MemberRepository backed by db's collab_members
// collection.
func NewMemberRepository(db *mongo.Database) *MemberRepository {
	return &MemberRepository{collection: db.Collection(MembersCollectionName)}
}

// EnsureIndexes creates the collab_members indexes:
//   - unique (collab_id, user_id): one membership per user and collab. It also serves
//     the caller's statuses for a page of collabs (collab_id $in, user_id equality).
//   - MemberRequestsIndex: the owner's pending counts and the requests list.
func (r *MemberRepository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "collab_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{
				{Key: "collab_id", Value: 1},
				{Key: "status", Value: 1},
				{Key: "requested_at", Value: 1},
				{Key: "user_id", Value: 1},
			},
			Options: options.Index().SetName(MemberRequestsIndex),
		},
	}
	if _, err := r.collection.Indexes().CreateMany(ctx, indexes); err != nil {
		return fmt.Errorf("ensure %s indexes: %w", MembersCollectionName, err)
	}

	return nil
}

// FindMember reads the stored status, updated_at and version of (collabID, userID).
func (r *MemberRepository) FindMember(ctx context.Context, collabID, userID uuid.UUID) (usecase.CollabMemberState, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var document memberDocument
	err := r.collection.FindOne(ctx, memberFilter(collabID, userID),
		options.FindOne().SetProjection(bson.D{{Key: "status", Value: 1}, {Key: "updated_at", Value: 1}, {Key: "version", Value: 1}}),
	).Decode(&document)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return usecase.CollabMemberState{}, false, nil
	}
	if err != nil {
		return usecase.CollabMemberState{}, false, fmt.Errorf("find collab %s member %s: %w", collabID, userID, err)
	}

	return usecase.CollabMemberState{Status: document.Status, UpdatedAt: document.UpdatedAt, Version: document.Version}, true, nil
}

// InsertMember creates the membership at version 1. It runs inside the handler's
// transaction after FindMember found nothing, so a duplicate-key error can only come
// from a concurrent first event of the same membership: it is returned as a retryable
// error (it has aborted the transaction anyway), and the redelivery compares against
// the row that writer created.
func (r *MemberRepository) InsertMember(ctx context.Context, input usecase.ApplyCollabMemberInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	document := memberDocument{
		CollabID:    input.CollabID.String(),
		UserID:      input.UserID.String(),
		Status:      input.Status,
		Role:        input.Role,
		RequestedAt: input.RequestedAt.UTC(),
		UpdatedAt:   input.OccurredAt.UTC(),
		Version:     1,
	}
	if _, err := r.collection.InsertOne(ctx, document); err != nil {
		return fmt.Errorf("insert collab %s member %s: %w", input.CollabID, input.UserID, err)
	}

	return nil
}

// UpdateMember moves the membership to input's status, role and updated_at, only while
// its version is still expectedVersion. requested_at never changes: post-command-service
// keeps one member row per user and collab, created by the request.
func (r *MemberRepository) UpdateMember(ctx context.Context, input usecase.ApplyCollabMemberInput, expectedVersion int64) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := append(memberFilter(input.CollabID, input.UserID), bson.E{Key: "version", Value: expectedVersion})
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "status", Value: input.Status},
			{Key: "role", Value: input.Role},
			{Key: "updated_at", Value: input.OccurredAt.UTC()},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}
	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update collab %s member %s: %w", input.CollabID, input.UserID, err)
	}
	if result.MatchedCount == 0 {
		return fmt.Errorf("update collab %s member %s: %w", input.CollabID, input.UserID, ErrVersionConflict)
	}

	return nil
}

func memberFilter(collabID, userID uuid.UUID) bson.D {
	return bson.D{
		{Key: "collab_id", Value: collabID.String()},
		{Key: "user_id", Value: userID.String()},
	}
}
