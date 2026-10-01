package recordlike_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase/recordlike"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    recordlike.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input recordlike.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheLikeReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := recordlike.New(repository)

	input := recordlike.Input{
		LikeID:    uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := usecase.Execute(context.Background(), input); err != nil {
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
	usecase := recordlike.New(&fakeRepository{})

	input := recordlike.Input{PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when like id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	usecase := recordlike.New(&fakeRepository{})

	input := recordlike.Input{LikeID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	usecase := recordlike.New(&fakeRepository{})

	input := recordlike.Input{LikeID: uuid.New(), PostID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	usecase := recordlike.New(&fakeRepository{err: wantErr})

	input := recordlike.Input{LikeID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
