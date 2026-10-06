package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

type fakeRecordCollabOpenedRepository struct {
	insertCalls int
	gotInput    usecase.RecordCollabOpenedInput
	err         error
}

func (f *fakeRecordCollabOpenedRepository) Insert(ctx context.Context, input usecase.RecordCollabOpenedInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestRecordCollabOpenedUsecase_Execute_InsertsTheCollabReadModelForAValidInput(t *testing.T) {
	repository := &fakeRecordCollabOpenedRepository{}
	uc := usecase.NewRecordCollabOpened(repository)

	input := usecase.RecordCollabOpenedInput{
		CollabID:    uuid.New(),
		PostID:      uuid.New(),
		OwnerUserID: uuid.New(),
		CreatedAt:   time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.CollabID != input.CollabID {
		t.Fatalf("Insert() CollabID = %v, want %v", repository.gotInput.CollabID, input.CollabID)
	}
}

func TestRecordCollabOpenedUsecase_Execute_ReturnsErrorWhenCollabIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordCollabOpened(&fakeRecordCollabOpenedRepository{})

	input := usecase.RecordCollabOpenedInput{PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestRecordCollabOpenedUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordCollabOpened(&fakeRecordCollabOpenedRepository{})

	input := usecase.RecordCollabOpenedInput{CollabID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestRecordCollabOpenedUsecase_Execute_ReturnsErrorWhenOwnerUserIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordCollabOpened(&fakeRecordCollabOpenedRepository{})

	input := usecase.RecordCollabOpenedInput{CollabID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when owner user id is missing")
	}
}

func TestRecordCollabOpenedUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewRecordCollabOpened(&fakeRecordCollabOpenedRepository{err: wantErr})

	input := usecase.RecordCollabOpenedInput{CollabID: uuid.New(), PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
