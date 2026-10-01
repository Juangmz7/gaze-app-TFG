package recordcomment_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase/recordcomment"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    recordcomment.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input recordcomment.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheCommentReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := recordcomment.New(repository)

	input := recordcomment.Input{
		CommentID: uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		Content:   "nice post",
		CreatedAt: time.Now().UTC(),
	}

	if err := usecase.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.CommentID != input.CommentID {
		t.Fatalf("Upsert() CommentID = %v, want %v", repository.gotInput.CommentID, input.CommentID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenCommentIDIsMissing(t *testing.T) {
	usecase := recordcomment.New(&fakeRepository{})

	input := recordcomment.Input{PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when comment id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	usecase := recordcomment.New(&fakeRepository{})

	input := recordcomment.Input{CommentID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	usecase := recordcomment.New(&fakeRepository{})

	input := recordcomment.Input{CommentID: uuid.New(), PostID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	usecase := recordcomment.New(&fakeRepository{err: wantErr})

	input := recordcomment.Input{CommentID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
