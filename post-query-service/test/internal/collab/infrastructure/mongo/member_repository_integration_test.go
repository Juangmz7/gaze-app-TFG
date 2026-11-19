package mongo_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
)

type storedMemberDocument struct {
	CollabID    string    `bson:"collab_id"`
	UserID      string    `bson:"user_id"`
	Status      string    `bson:"status"`
	Role        string    `bson:"role"`
	RequestedAt time.Time `bson:"requested_at"`
	UpdatedAt   time.Time `bson:"updated_at"`
	Version     int64     `bson:"version"`
}

func TestMemberRepository_PersistsAndVersionGatesAMembership(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	db := testDatabase(t, ctx)
	repository := collabmongo.NewMemberRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	collabID, userID := uuid.New(), uuid.New()
	requestedAt := time.Date(2026, 3, 1, 10, 15, 0, 500000000, time.UTC)
	input := usecase.ApplyCollabMemberInput{
		CollabID: collabID, UserID: userID, Status: usecase.CollabMemberStatusPending, Role: usecase.CollabMemberRoleMember,
		RequestedAt: requestedAt, OccurredAt: requestedAt.Add(12 * time.Millisecond),
	}

	if _, found, err := repository.FindMember(ctx, collabID, userID); err != nil || found {
		t.Fatalf("FindMember() before insert = found %v, error %v, want not found", found, err)
	}
	if err := repository.InsertMember(ctx, input); err != nil {
		t.Fatalf("InsertMember() error = %v", err)
	}
	state, found, err := repository.FindMember(ctx, collabID, userID)
	if err != nil || !found || state.Status != usecase.CollabMemberStatusPending || state.Version != 1 || !state.UpdatedAt.Equal(input.OccurredAt) {
		t.Fatalf("FindMember() = %+v, %v, %v, want PENDING at version 1 updated at the event time", state, found, err)
	}

	// A second insert of the same membership hits the unique index: a retryable error.
	if err := repository.InsertMember(ctx, input); err == nil {
		t.Fatal("InsertMember() of an existing membership error = nil, want the duplicate-key error")
	}

	accepted := input
	accepted.Status, accepted.Role = usecase.CollabMemberStatusAccepted, usecase.CollabMemberRoleAdmin
	accepted.OccurredAt = requestedAt.Add(time.Minute)
	accepted.RequestedAt = requestedAt.Add(time.Hour)
	if err := repository.UpdateMember(ctx, accepted, 1); err != nil {
		t.Fatalf("UpdateMember() error = %v", err)
	}
	if err := repository.UpdateMember(ctx, accepted, 1); !errors.Is(err, collabmongo.ErrVersionConflict) {
		t.Fatalf("UpdateMember() with a stale version error = %v, want ErrVersionConflict", err)
	}

	var stored storedMemberDocument
	filter := bson.D{{Key: "collab_id", Value: collabID.String()}, {Key: "user_id", Value: userID.String()}}
	if err := db.Collection(collabmongo.MembersCollectionName).FindOne(ctx, filter).Decode(&stored); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	want := storedMemberDocument{
		CollabID: collabID.String(), UserID: userID.String(), Status: "ACCEPTED", Role: "ADMIN",
		RequestedAt: requestedAt, UpdatedAt: requestedAt.Add(time.Minute), Version: 2,
	}
	if stored != want {
		t.Fatalf("stored = %+v, want %+v (requested_at is never rewritten)", stored, want)
	}
}

func TestMemberRepository_EnsureIndexes_CreatesTheUniqueAndTheRequestsIndexes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	db := testDatabase(t, ctx)
	repository := collabmongo.NewMemberRepository(db)
	for range 2 {
		if err := repository.EnsureIndexes(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v, want it idempotent", err)
		}
	}

	cursor, err := db.Collection(collabmongo.MembersCollectionName).Indexes().List(ctx)
	if err != nil {
		t.Fatalf("Indexes().List() error = %v", err)
	}
	var indexes []struct {
		Name   string `bson:"name"`
		Key    bson.D `bson:"key"`
		Unique bool   `bson:"unique"`
	}
	if err := cursor.All(ctx, &indexes); err != nil {
		t.Fatalf("cursor.All() error = %v", err)
	}

	byName := map[string]bson.D{}
	unique := map[string]bool{}
	for _, index := range indexes {
		byName[index.Name] = index.Key
		unique[index.Name] = index.Unique
	}
	if !unique["collab_id_1_user_id_1"] {
		t.Fatalf("indexes = %v, want a unique (collab_id, user_id)", indexes)
	}
	keys, found := byName[collabmongo.MemberRequestsIndex]
	if !found || len(keys) != 4 || keys[0].Key != "collab_id" || keys[1].Key != "status" || keys[2].Key != "requested_at" || keys[3].Key != "user_id" {
		t.Fatalf("%s keys = %v, want {collab_id, status, requested_at, user_id}", collabmongo.MemberRequestsIndex, keys)
	}
}
