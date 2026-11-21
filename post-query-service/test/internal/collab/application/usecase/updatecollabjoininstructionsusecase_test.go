package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

// fakeJoinInstructionsStore holds one collab's join instructions the way the read model
// does: text, update time and version.
type fakeJoinInstructionsStore struct {
	found        bool
	instructions string
	updatedAt    time.Time
	version      int64
	updateCalls  int
	getErr       error
	updateErr    error
}

func (f *fakeJoinInstructionsStore) GetJoinInstructionsState(context.Context, uuid.UUID) (usecase.CollabJoinInstructionsState, bool, error) {
	if f.getErr != nil {
		return usecase.CollabJoinInstructionsState{}, false, f.getErr
	}
	return usecase.CollabJoinInstructionsState{Version: f.version, UpdatedAt: f.updatedAt}, f.found, nil
}

func (f *fakeJoinInstructionsStore) UpdateJoinInstructions(_ context.Context, input usecase.UpdateCollabJoinInstructionsInput) error {
	f.updateCalls++
	if f.updateErr != nil {
		return f.updateErr
	}
	if input.ExpectedVersion != f.version {
		return errors.New("version conflict")
	}
	f.instructions, f.updatedAt, f.version = input.JoinInstructions, input.OccurredAt, f.version+1
	return nil
}

var joinInstructionsOpenedAt = time.Date(2026, 3, 1, 10, 15, 0, 0, time.UTC)

func openedJoinInstructionsStore() *fakeJoinInstructionsStore {
	return &fakeJoinInstructionsStore{found: true, instructions: "Opening text", updatedAt: joinInstructionsOpenedAt, version: 1}
}

func TestUpdateCollabJoinInstructionsUsecase_Execute_AppliesUpdatesOnlyForwardInTime(t *testing.T) {
	store := openedJoinInstructionsStore()
	uc := usecase.NewUpdateCollabJoinInstructions(store)
	collabID := uuid.New()

	steps := []struct {
		name        string
		text        string
		occurredAt  time.Time
		wantApplied bool
		wantText    string
	}{
		{name: "newer update", text: "  Chat: https://chat.example.test/harbour  ", occurredAt: joinInstructionsOpenedAt.Add(time.Hour), wantApplied: true, wantText: "Chat: https://chat.example.test/harbour"},
		{name: "older update is stale", text: "Outdated", occurredAt: joinInstructionsOpenedAt.Add(30 * time.Minute), wantApplied: false, wantText: "Chat: https://chat.example.test/harbour"},
		{name: "redelivery at the same millisecond is stale", text: "Redelivered", occurredAt: joinInstructionsOpenedAt.Add(time.Hour + 400*time.Microsecond), wantApplied: false, wantText: "Chat: https://chat.example.test/harbour"},
		{name: "cleared", text: "   ", occurredAt: joinInstructionsOpenedAt.Add(2 * time.Hour), wantApplied: true, wantText: ""},
	}

	for _, step := range steps {
		applied, err := uc.Execute(context.Background(), usecase.UpdateCollabJoinInstructionsInput{CollabID: collabID, JoinInstructions: step.text, OccurredAt: step.occurredAt})
		if err != nil {
			t.Fatalf("%s: Execute() error = %v", step.name, err)
		}
		if applied != step.wantApplied || store.instructions != step.wantText {
			t.Fatalf("%s: applied = %v, stored %q, want %v and %q", step.name, applied, store.instructions, step.wantApplied, step.wantText)
		}
	}
	if store.version != 3 {
		t.Fatalf("version = %d, want 3: two writes on top of the opening", store.version)
	}
}

func TestUpdateCollabJoinInstructionsUsecase_Execute_StoresTheTimeTruncatedToTheMillisecond(t *testing.T) {
	store := openedJoinInstructionsStore()
	occurredAt := joinInstructionsOpenedAt.Add(time.Hour + 123456*time.Microsecond)

	if _, err := usecase.NewUpdateCollabJoinInstructions(store).Execute(context.Background(), usecase.UpdateCollabJoinInstructionsInput{CollabID: uuid.New(), JoinInstructions: "Text", OccurredAt: occurredAt}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if want := occurredAt.Truncate(time.Millisecond); !store.updatedAt.Equal(want) {
		t.Fatalf("stored update time = %v, want %v", store.updatedAt, want)
	}
}

func TestUpdateCollabJoinInstructionsUsecase_Execute_ReturnsARetryableErrorWhenTheCollabIsNotProjectedYet(t *testing.T) {
	store := &fakeJoinInstructionsStore{}

	applied, err := usecase.NewUpdateCollabJoinInstructions(store).Execute(context.Background(), usecase.UpdateCollabJoinInstructionsInput{CollabID: uuid.New(), JoinInstructions: "Text", OccurredAt: joinInstructionsOpenedAt})
	if err == nil || applied {
		t.Fatalf("Execute() = %v, %v, want an error and nothing applied", applied, err)
	}
	if store.updateCalls != 0 {
		t.Fatalf("UpdateJoinInstructions() calls = %d, want 0", store.updateCalls)
	}
}

func TestUpdateCollabJoinInstructionsUsecase_Execute_PropagatesRepositoryFailures(t *testing.T) {
	failure := errors.New("mongo unavailable")
	for name, store := range map[string]*fakeJoinInstructionsStore{
		"read fails":  {getErr: failure},
		"write fails": {found: true, updatedAt: joinInstructionsOpenedAt, version: 1, updateErr: failure},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := usecase.NewUpdateCollabJoinInstructions(store).Execute(context.Background(), usecase.UpdateCollabJoinInstructionsInput{CollabID: uuid.New(), OccurredAt: joinInstructionsOpenedAt.Add(time.Hour)})
			if !errors.Is(err, failure) {
				t.Fatalf("Execute() error = %v, want %v", err, failure)
			}
		})
	}
}

func TestUpdateCollabJoinInstructionsUsecase_Execute_RejectsAnIncompleteInput(t *testing.T) {
	for name, input := range map[string]usecase.UpdateCollabJoinInstructionsInput{
		"no collab id":   {OccurredAt: joinInstructionsOpenedAt},
		"no occurred at": {CollabID: uuid.New()},
	} {
		t.Run(name, func(t *testing.T) {
			store := openedJoinInstructionsStore()
			if _, err := usecase.NewUpdateCollabJoinInstructions(store).Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want an error")
			}
			if store.updateCalls != 0 {
				t.Fatalf("UpdateJoinInstructions() calls = %d, want 0", store.updateCalls)
			}
		})
	}
}

func TestRecordCollabOpenedUsecase_Execute_StoresTrimmedJoinInstructionsAndTheOpeningTime(t *testing.T) {
	repository := &fakeRecordCollabOpenedRepository{}
	occurredAt := joinInstructionsOpenedAt.Add(123456 * time.Microsecond)

	err := usecase.NewRecordCollabOpened(repository).Execute(context.Background(), usecase.RecordCollabOpenedInput{
		CollabID: uuid.New(), PostID: uuid.New(), OwnerUserID: uuid.New(), CreatedAt: joinInstructionsOpenedAt,
		JoinInstructions: "\n  Welcome! https://chat.example.test/harbour \n", OccurredAt: occurredAt,
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if got := repository.gotInput.JoinInstructions; got != "Welcome! https://chat.example.test/harbour" {
		t.Fatalf("Insert() JoinInstructions = %q, want the trimmed text", got)
	}
	if want := occurredAt.Truncate(time.Millisecond); !repository.gotInput.OccurredAt.Equal(want) {
		t.Fatalf("Insert() OccurredAt = %v, want %v", repository.gotInput.OccurredAt, want)
	}
}
