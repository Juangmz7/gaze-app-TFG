package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

type fakeUpdateUserRepository struct {
	version      int64
	found        bool
	getVersionEr error
	updateCalls  int
	gotInput     usecase.UpdateUserInput
	updateErr    error
}

func (f *fakeUpdateUserRepository) GetVersion(ctx context.Context, userID uuid.UUID) (int64, bool, error) {
	return f.version, f.found, f.getVersionEr
}

func (f *fakeUpdateUserRepository) Update(ctx context.Context, input usecase.UpdateUserInput) error {
	f.updateCalls++
	f.gotInput = input
	return f.updateErr
}

func validUpdateUserInput() usecase.UpdateUserInput {
	return usecase.UpdateUserInput{
		UserID:        uuid.New(),
		Username:      "ada-lovelace-renamed",
		Email:         "ada@example.com",
		AccountStatus: "ACCEPTED",
	}
}

func TestUpdateUserUsecase_Execute_UpdatesTheUserReadModelWithTheCurrentVersion(t *testing.T) {
	repository := &fakeUpdateUserRepository{version: 3, found: true}
	uc := usecase.NewUpdateUser(repository)

	input := validUpdateUserInput()
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.updateCalls != 1 {
		t.Fatalf("Update() calls = %d, want 1", repository.updateCalls)
	}
	if repository.gotInput.ExpectedVersion != 3 {
		t.Fatalf("Update() ExpectedVersion = %d, want 3 (from GetVersion)", repository.gotInput.ExpectedVersion)
	}
	if repository.gotInput.Username != input.Username {
		t.Fatalf("Update() Username = %q, want %q", repository.gotInput.Username, input.Username)
	}
}

func TestUpdateUserUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewUpdateUser(&fakeUpdateUserRepository{})

	input := validUpdateUserInput()
	input.UserID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUpdateUserUsecase_Execute_ReturnsErrorWhenUsernameIsMissing(t *testing.T) {
	uc := usecase.NewUpdateUser(&fakeUpdateUserRepository{})

	input := validUpdateUserInput()
	input.Username = ""

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when username is missing")
	}
}

func TestUpdateUserUsecase_Execute_ReturnsErrorWhenTheUserHasNoProjectionYet(t *testing.T) {
	repository := &fakeUpdateUserRepository{found: false}
	uc := usecase.NewUpdateUser(repository)

	if err := uc.Execute(context.Background(), validUpdateUserInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error when the user was never projected (user-registered not seen yet)")
	}
	if repository.updateCalls != 0 {
		t.Fatalf("Update() calls = %d, want 0 when GetVersion reports not found", repository.updateCalls)
	}
}

func TestUpdateUserUsecase_Execute_PropagatesTheGetVersionError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	uc := usecase.NewUpdateUser(&fakeUpdateUserRepository{getVersionEr: wantErr})

	if err := uc.Execute(context.Background(), validUpdateUserInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestUpdateUserUsecase_Execute_PropagatesTheUpdateError(t *testing.T) {
	wantErr := errors.New("version conflict")
	uc := usecase.NewUpdateUser(&fakeUpdateUserRepository{version: 1, found: true, updateErr: wantErr})

	if err := uc.Execute(context.Background(), validUpdateUserInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
