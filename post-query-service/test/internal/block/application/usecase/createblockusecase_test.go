package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
)

type fakeCreateBlockRepository struct {
	insertCalls int
	gotInput    usecase.CreateBlockInput
	err         error
}

func (f *fakeCreateBlockRepository) Insert(ctx context.Context, input usecase.CreateBlockInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestCreateBlockUsecase_Execute_InsertsTheBlockReadModelForAValidInput(t *testing.T) {
	repository := &fakeCreateBlockRepository{}
	uc := usecase.NewCreateBlock(repository)

	input := usecase.CreateBlockInput{
		BlockerUserID: uuid.New(),
		BlockedUserID: uuid.New(),
		CreatedAt:     time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.BlockerUserID != input.BlockerUserID {
		t.Fatalf("Insert() BlockerUserID = %v, want %v", repository.gotInput.BlockerUserID, input.BlockerUserID)
	}
}

func TestCreateBlockUsecase_Execute_ReturnsErrorWhenBlockerUserIDIsMissing(t *testing.T) {
	uc := usecase.NewCreateBlock(&fakeCreateBlockRepository{})

	input := usecase.CreateBlockInput{BlockedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when blocker user id is missing")
	}
}

func TestCreateBlockUsecase_Execute_ReturnsErrorWhenBlockedUserIDIsMissing(t *testing.T) {
	uc := usecase.NewCreateBlock(&fakeCreateBlockRepository{})

	input := usecase.CreateBlockInput{BlockerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when blocked user id is missing")
	}
}

func TestCreateBlockUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewCreateBlock(&fakeCreateBlockRepository{err: wantErr})

	input := usecase.CreateBlockInput{BlockerUserID: uuid.New(), BlockedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
