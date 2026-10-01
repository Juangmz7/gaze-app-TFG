package recordcollabopened_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase/recordcollabopened"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    recordcollabopened.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input recordcollabopened.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheCollabReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := recordcollabopened.New(repository)

	input := recordcollabopened.Input{
		CollabID:    uuid.New(),
		PostID:      uuid.New(),
		OwnerUserID: uuid.New(),
		CreatedAt:   time.Now().UTC(),
	}

	if err := usecase.Execute(context.Background(), input); err != nil {
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
	usecase := recordcollabopened.New(&fakeRepository{})

	input := recordcollabopened.Input{PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	usecase := recordcollabopened.New(&fakeRepository{})

	input := recordcollabopened.Input{CollabID: uuid.New(), OwnerUserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenOwnerUserIDIsMissing(t *testing.T) {
	usecase := recordcollabopened.New(&fakeRepository{})

	input := recordcollabopened.Input{CollabID: uuid.New(), PostID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when owner user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	usecase := recordcollabopened.New(&fakeRepository{err: wantErr})

	input := recordcollabopened.Input{CollabID: uuid.New(), PostID: uuid.New(), OwnerUserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
