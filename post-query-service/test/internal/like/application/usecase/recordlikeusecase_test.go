package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
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

func TestUsecase_Execute_UpsertsTheLikeReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	uc := usecase.New(repository)

	input := usecase.Input{
		LikeID:    uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.LikeID != input.LikeID {
		t.Fatalf("Upsert() LikeID = %v, want %v", repository.gotInput.LikeID, input.LikeID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenLikeIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when like id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{LikeID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{LikeID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.New(&fakeRepository{err: wantErr})

	input := usecase.Input{LikeID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
