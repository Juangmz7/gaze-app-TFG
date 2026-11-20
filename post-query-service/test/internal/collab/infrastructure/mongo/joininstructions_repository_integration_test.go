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

type joinInstructionsDocument struct {
	Title            string    `bson:"title"`
	Status           string    `bson:"status"`
	ClosedBy         string    `bson:"closed_by"`
	JoinInstructions string    `bson:"join_instructions"`
	UpdatedAt        time.Time `bson:"join_instructions_updated_at"`
	Version          int64     `bson:"version"`
}

// One MongoDB for the whole join instructions flow of the repository (task 61).
func TestRepository_JoinInstructions(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := collabmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}
	openedAt := time.Date(2026, 3, 1, 10, 15, 0, 123000000, time.UTC)

	find := func(t *testing.T, collabID uuid.UUID) joinInstructionsDocument {
		t.Helper()
		var doc joinInstructionsDocument
		if err := db.Collection("post_collabs").FindOne(ctx, bson.D{{Key: "collab_id", Value: collabID.String()}}).Decode(&doc); err != nil {
			t.Fatalf("FindOne() error = %v", err)
		}
		return doc
	}
	open := func(t *testing.T, instructions string) uuid.UUID {
		t.Helper()
		collabID := uuid.New()
		err := repository.Insert(ctx, usecase.RecordCollabOpenedInput{
			CollabID: collabID, PostID: uuid.New(), OwnerUserID: uuid.New(), Title: "Harbour series", Status: "OPEN",
			CreatedAt: openedAt, JoinInstructions: instructions, OccurredAt: openedAt,
		})
		if err != nil {
			t.Fatalf("Insert() error = %v", err)
		}
		return collabID
	}

	t.Run("Insert stores the opening instructions and their time", func(t *testing.T) {
		collabID := open(t, "Welcome! https://chat.example.test/harbour")

		got := find(t, collabID)
		if got.JoinInstructions != "Welcome! https://chat.example.test/harbour" || !got.UpdatedAt.Equal(openedAt) {
			t.Fatalf("stored = %q at %v, want the opening text at %v", got.JoinInstructions, got.UpdatedAt, openedAt)
		}
		state, found, err := repository.GetJoinInstructionsState(ctx, collabID)
		if err != nil || !found || state.Version != 1 || !state.UpdatedAt.Equal(openedAt) {
			t.Fatalf("GetJoinInstructionsState() = %+v, %v, %v, want version 1 at %v", state, found, err, openedAt)
		}
	})

	t.Run("GetJoinInstructionsState reports an unknown collab as not found", func(t *testing.T) {
		if _, found, err := repository.GetJoinInstructionsState(ctx, uuid.New()); err != nil || found {
			t.Fatalf("GetJoinInstructionsState() found = %v, error = %v, want false and nil", found, err)
		}
	})

	t.Run("UpdateJoinInstructions overwrites, then clears, touching nothing else", func(t *testing.T) {
		collabID := open(t, "Opening text")

		first := openedAt.Add(time.Hour)
		if err := repository.UpdateJoinInstructions(ctx, usecase.UpdateCollabJoinInstructionsInput{CollabID: collabID, JoinInstructions: "Repo: https://git.example.test/harbour", OccurredAt: first, ExpectedVersion: 1}); err != nil {
			t.Fatalf("UpdateJoinInstructions() error = %v", err)
		}
		if got := find(t, collabID); got.JoinInstructions != "Repo: https://git.example.test/harbour" || !got.UpdatedAt.Equal(first) || got.Version != 2 {
			t.Fatalf("after the first update = %+v, want the new text at %v, version 2", got, first)
		}

		second := openedAt.Add(2 * time.Hour)
		if err := repository.UpdateJoinInstructions(ctx, usecase.UpdateCollabJoinInstructionsInput{CollabID: collabID, OccurredAt: second, ExpectedVersion: 2}); err != nil {
			t.Fatalf("UpdateJoinInstructions() error = %v", err)
		}
		got := find(t, collabID)
		if got.JoinInstructions != "" || !got.UpdatedAt.Equal(second) || got.Version != 3 {
			t.Fatalf("after clearing = %+v, want empty text at %v, version 3", got, second)
		}
		if got.Title != "Harbour series" || got.Status != "OPEN" || got.ClosedBy != "" {
			t.Fatalf("other fields = %+v, want them untouched", got)
		}
	})

	t.Run("UpdateJoinInstructions reports a moved version as ErrVersionConflict", func(t *testing.T) {
		collabID := open(t, "Opening text")

		err := repository.UpdateJoinInstructions(ctx, usecase.UpdateCollabJoinInstructionsInput{CollabID: collabID, JoinInstructions: "Lost race", OccurredAt: openedAt.Add(time.Hour), ExpectedVersion: 7})
		if !errors.Is(err, collabmongo.ErrVersionConflict) {
			t.Fatalf("UpdateJoinInstructions() error = %v, want ErrVersionConflict", err)
		}
		if got := find(t, collabID); got.JoinInstructions != "Opening text" || got.Version != 1 {
			t.Fatalf("after a conflict = %+v, want the opening text at version 1", got)
		}
	})

	t.Run("closing the collab keeps its instructions", func(t *testing.T) {
		collabID := open(t, "Opening text")

		if err := repository.Update(ctx, usecase.CloseCollabInput{CollabID: collabID, ExpectedVersion: 1, Title: "Harbour series", CreatedBy: uuid.New(), ClosedBy: uuid.New(), Status: "CLOSED"}); err != nil {
			t.Fatalf("Update() error = %v", err)
		}
		if got := find(t, collabID); got.JoinInstructions != "Opening text" || got.Status != "CLOSED" {
			t.Fatalf("after closing = %+v, want the instructions kept", got)
		}
	})
}
