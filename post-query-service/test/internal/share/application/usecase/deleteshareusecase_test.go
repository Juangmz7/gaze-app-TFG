package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

type fakeDeleteShareRepository struct {
	deleteCalls int
	gotPostID   uuid.UUID
	gotUserID   uuid.UUID
	err         error
}

func (f *fakeDeleteShareRepository) DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) error {
	f.deleteCalls++
	f.gotPostID = postID
	f.gotUserID = userID
	return f.err
}

func TestDeleteShareUsecase_Execute_DeletesTheShareReadModelByPostAndUser(t *testing.T) {
	repository := &fakeDeleteShareRepository{}
	uc := usecase.NewDeleteShare(repository)

	postID := uuid.New()
	userID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeleteShareInput{PostID: postID, UserID: userID}); err != nil {
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

func TestDeleteShareUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteShareInput{UserID: uuid.New()}); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestDeleteShareUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteShareInput{PostID: uuid.New()}); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestDeleteShareUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeleteShareInput{PostID: uuid.New(), UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
