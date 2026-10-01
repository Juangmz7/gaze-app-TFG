package deleteuser_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase/deleteuser"
)

type fakeRepository struct {
	deleteCalls int
	gotUserID   uuid.UUID
	err         error
}

func (f *fakeRepository) Delete(ctx context.Context, userID uuid.UUID) error {
	f.deleteCalls++
	f.gotUserID = userID
	return f.err
}

func TestUsecase_Execute_DeletesTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := deleteuser.New(repository)

	userID := uuid.New()

	if err := usecase.Execute(context.Background(), deleteuser.Input{UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotUserID != userID {
		t.Fatalf("Delete() userID = %v, want %v", repository.gotUserID, userID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	usecase := deleteuser.New(&fakeRepository{})

	if err := usecase.Execute(context.Background(), deleteuser.Input{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	usecase := deleteuser.New(&fakeRepository{err: wantErr})

	err := usecase.Execute(context.Background(), deleteuser.Input{UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
