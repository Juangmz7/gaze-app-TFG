package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

type fakeRepository struct {
	insertCalls int
	gotInput    usecase.Input
	err         error
}

func (f *fakeRepository) Insert(ctx context.Context, input usecase.Input) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_InsertsTheCommentReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	uc := usecase.New(repository)

	input := usecase.Input{
		CommentID: uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		Content:   "nice post",
		CreatedAt: time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.CommentID != input.CommentID {
		t.Fatalf("Insert() CommentID = %v, want %v", repository.gotInput.CommentID, input.CommentID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenCommentIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when comment id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{CommentID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.New(&fakeRepository{})

	input := usecase.Input{CommentID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.New(&fakeRepository{err: wantErr})

	input := usecase.Input{CommentID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
