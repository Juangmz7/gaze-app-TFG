package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
)

type fakeDeleteFollowRepository struct {
	deleteCalls int
	gotFollower uuid.UUID
	gotFollowed uuid.UUID
	err         error
}

func (f *fakeDeleteFollowRepository) Delete(ctx context.Context, followerUserID, followedUserID uuid.UUID) error {
	f.deleteCalls++
	f.gotFollower = followerUserID
	f.gotFollowed = followedUserID
	return f.err
}

func TestDeleteFollowUsecase_Execute_DeletesTheFollowReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteFollowRepository{}
	uc := usecase.NewDeleteFollow(repository)

	input := usecase.DeleteFollowInput{FollowerUserID: uuid.New(), FollowedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotFollower != input.FollowerUserID {
		t.Fatalf("Delete() followerUserID = %v, want %v", repository.gotFollower, input.FollowerUserID)
	}
}

func TestDeleteFollowUsecase_Execute_ReturnsErrorWhenFollowerUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteFollow(&fakeDeleteFollowRepository{})

	input := usecase.DeleteFollowInput{FollowedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when follower user id is missing")
	}
}

func TestDeleteFollowUsecase_Execute_ReturnsErrorWhenFollowedUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteFollow(&fakeDeleteFollowRepository{})

	input := usecase.DeleteFollowInput{FollowerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when followed user id is missing")
	}
}

func TestDeleteFollowUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewDeleteFollow(&fakeDeleteFollowRepository{err: wantErr})

	input := usecase.DeleteFollowInput{FollowerUserID: uuid.New(), FollowedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
