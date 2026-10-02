package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    usecase.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input usecase.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheCollabReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	uc := usecase.New(repository)

	input := usecase.Input{
		CollabID:    uuid.New(),
		PostID:      uuid.New(),
		OwnerUserID: uuid.New(),
		CreatedAt:   time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.CollabID != input.CollabID {
		t.Fatalf("Upsert() CollabID = %v, want %v", repository.gotInput.CollabID, input.CollabID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenCollabIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{CollabID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenOwnerUserIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{CollabID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when owner user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.New(&fakeRepository{err: wantErr})

	input := usecase.Input{CollabID: uuid.New(), PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
