package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
)

type fakeDeleteBlockRepository struct {
	deleteCalls int
	gotBlocker  uuid.UUID
	gotBlocked  uuid.UUID
	err         error
}

func (f *fakeDeleteBlockRepository) Delete(ctx context.Context, blockerUserID, blockedUserID uuid.UUID) error {
	f.deleteCalls++
	f.gotBlocker = blockerUserID
	f.gotBlocked = blockedUserID
	return f.err
}

func TestDeleteBlockUsecase_Execute_DeletesTheBlockReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteBlockRepository{}
	uc := usecase.NewDeleteBlock(repository)

	input := usecase.DeleteBlockInput{BlockerUserID: uuid.New(), BlockedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotBlocker != input.BlockerUserID {
		t.Fatalf("Delete() blockerUserID = %v, want %v", repository.gotBlocker, input.BlockerUserID)
	}
}

func TestDeleteBlockUsecase_Execute_ReturnsErrorWhenBlockerUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteBlock(&fakeDeleteBlockRepository{})

	input := usecase.DeleteBlockInput{BlockedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when blocker user id is missing")
	}
}

func TestDeleteBlockUsecase_Execute_ReturnsErrorWhenBlockedUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteBlock(&fakeDeleteBlockRepository{})

	input := usecase.DeleteBlockInput{BlockerUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when blocked user id is missing")
	}
}

func TestDeleteBlockUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewDeleteBlock(&fakeDeleteBlockRepository{err: wantErr})

	input := usecase.DeleteBlockInput{BlockerUserID: uuid.New(), BlockedUserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
