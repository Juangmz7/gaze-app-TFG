package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
)

type fakeCreateFollowRepository struct {
	insertCalls int
	gotInput    usecase.CreateFollowInput
	err         error
}

func (f *fakeCreateFollowRepository) Insert(ctx context.Context, input usecase.CreateFollowInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestCreateFollowUsecase_Execute_InsertsTheFollowReadModelForAValidInput(t *testing.T) {
	repository := &fakeCreateFollowRepository{}
	uc := usecase.NewCreateFollow(repository)

	input := usecase.CreateFollowInput{
		FollowerUserID: uuid.New(),
		FollowedUserID: uuid.New(),
		CreatedAt:      time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.FollowerUserID != input.FollowerUserID {
		t.Fatalf("Insert() FollowerUserID = %v, want %v", repository.gotInput.FollowerUserID, input.FollowerUserID)
	}
}

func TestCreateFollowUsecase_Execute_ReturnsErrorWhenFollowerUserIDIsMissing(t *testing.T) {
	uc := usecase.NewCreateFollow(&fakeCreateFollowRepository{})

	input := usecase.CreateFollowInput{FollowedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when follower user id is missing")
	}
}

func TestCreateFollowUsecase_Execute_ReturnsErrorWhenFollowedUserIDIsMissing(t *testing.T) {
	uc := usecase.NewCreateFollow(&fakeCreateFollowRepository{})

	input := usecase.CreateFollowInput{FollowerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when followed user id is missing")
	}
}

func TestCreateFollowUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewCreateFollow(&fakeCreateFollowRepository{err: wantErr})

	input := usecase.CreateFollowInput{FollowerUserID: uuid.New(), FollowedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
