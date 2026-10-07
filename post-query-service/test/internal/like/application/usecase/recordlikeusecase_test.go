package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
)

type fakeRecordLikeRepository struct {
	insertCalls int
	gotInput    usecase.RecordLikeInput
	err         error
}

func (f *fakeRecordLikeRepository) Insert(ctx context.Context, input usecase.RecordLikeInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestRecordLikeUsecase_Execute_InsertsTheLikeReadModelForAValidInput(t *testing.T) {
	repository := &fakeRecordLikeRepository{}
	uc := usecase.NewRecordLike(repository)

	input := usecase.RecordLikeInput{
		LikeID:    uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.LikeID != input.LikeID {
		t.Fatalf("Insert() LikeID = %v, want %v", repository.gotInput.LikeID, input.LikeID)
	}
}

func TestRecordLikeUsecase_Execute_ReturnsErrorWhenLikeIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{})

	input := usecase.RecordLikeInput{PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when like id is missing")
	}
}

func TestRecordLikeUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{})

	input := usecase.RecordLikeInput{LikeID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestRecordLikeUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{})

	input := usecase.RecordLikeInput{LikeID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestRecordLikeUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{err: wantErr})

	input := usecase.RecordLikeInput{LikeID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
