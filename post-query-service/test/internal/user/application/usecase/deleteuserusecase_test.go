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

type fakeDeleteUserFeedSessionRepository struct {
	gotUserID uuid.UUID
	err       error
}

func (f *fakeDeleteUserFeedSessionRepository) DeleteByUser(ctx context.Context, userID uuid.UUID) error {
	f.gotUserID = userID
	return f.err
}

type fakeDeleteUserFeedUnitRepository struct {
	gotUserID uuid.UUID
	err       error
}

func (f *fakeDeleteUserFeedUnitRepository) DeleteByUserOrCreator(ctx context.Context, userID uuid.UUID) error {
	f.gotUserID = userID
	return f.err
}

func TestDeleteUserUsecase_Execute_DeletesTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteUserRepository{}
	sessions := &fakeDeleteUserFeedSessionRepository{}
	units := &fakeDeleteUserFeedUnitRepository{}
	uc := usecase.NewDeleteUser(repository, sessions, units)

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
	if sessions.gotUserID != userID {
		t.Fatalf("DeleteByUser() userID = %v, want %v", sessions.gotUserID, userID)
	}
	if units.gotUserID != userID {
		t.Fatalf("DeleteByUserOrCreator() userID = %v, want %v", units.gotUserID, userID)
	}
}

func TestDeleteUserUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteUser(&fakeDeleteUserRepository{}, &fakeDeleteUserFeedSessionRepository{}, &fakeDeleteUserFeedUnitRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteUserInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestDeleteUserUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteUser(&fakeDeleteUserRepository{err: wantErr}, &fakeDeleteUserFeedSessionRepository{}, &fakeDeleteUserFeedUnitRepository{})

	err := uc.Execute(context.Background(), usecase.DeleteUserInput{UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestDeleteUserUsecase_Execute_PropagatesTheFeedRepositoryErrors(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	for name, uc := range map[string]*usecase.DeleteUserUsecase{
		"session": usecase.NewDeleteUser(&fakeDeleteUserRepository{}, &fakeDeleteUserFeedSessionRepository{err: wantErr}, &fakeDeleteUserFeedUnitRepository{}),
		"units":   usecase.NewDeleteUser(&fakeDeleteUserRepository{}, &fakeDeleteUserFeedSessionRepository{}, &fakeDeleteUserFeedUnitRepository{err: wantErr}),
	} {
		t.Run(name, func(t *testing.T) {
			err := uc.Execute(context.Background(), usecase.DeleteUserInput{UserID: uuid.New()})
			if !errors.Is(err, wantErr) {
				t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
			}
		})
	}
}
