package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

type fakeDeleteUserRepository struct {
	deleteCalls int
	gotUserID   uuid.UUID
	err         error
}

func (f *fakeDeleteUserRepository) Delete(ctx context.Context, userID uuid.UUID) error {
	f.deleteCalls++
	f.gotUserID = userID
	return f.err
}

func TestDeleteUserUsecase_Execute_DeletesTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteUserRepository{}
	uc := usecase.NewDeleteUser(repository)

	userID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeleteUserInput{UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotUserID != userID {
		t.Fatalf("Delete() userID = %v, want %v", repository.gotUserID, userID)
	}
}

func TestDeleteUserUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteUser(&fakeDeleteUserRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteUserInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestDeleteUserUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteUser(&fakeDeleteUserRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeleteUserInput{UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
