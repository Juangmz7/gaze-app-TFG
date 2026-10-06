package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
)

type fakeDeleteLikeRepository struct {
	deleteCalls int
	gotPostID   uuid.UUID
	gotUserID   uuid.UUID
	err         error
}

func (f *fakeDeleteLikeRepository) DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) error {
	f.deleteCalls++
	f.gotPostID = postID
	f.gotUserID = userID
	return f.err
}

func TestDeleteLikeUsecase_Execute_DeletesTheLikeReadModelByPostAndUser(t *testing.T) {
	repository := &fakeDeleteLikeRepository{}
	uc := usecase.NewDeleteLike(repository)

	postID := uuid.New()
	userID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeleteLikeInput{PostID: postID, UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("DeleteByPostAndUser() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotPostID != postID {
		t.Fatalf("DeleteByPostAndUser() postID = %v, want %v", repository.gotPostID, postID)
	}
	if repository.gotUserID != userID {
		t.Fatalf("DeleteByPostAndUser() userID = %v, want %v", repository.gotUserID, userID)
	}
}

func TestDeleteLikeUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteLike(&fakeDeleteLikeRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteLikeInput{UserID: uuid.New()}); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestDeleteLikeUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteLike(&fakeDeleteLikeRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteLikeInput{PostID: uuid.New()}); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestDeleteLikeUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteLike(&fakeDeleteLikeRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeleteLikeInput{PostID: uuid.New(), UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
