package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

type fakeRegisterUserRepository struct {
	upsertCalls int
	gotInput    usecase.RegisterUserInput
	err         error
}

func (f *fakeRegisterUserRepository) Upsert(ctx context.Context, input usecase.RegisterUserInput) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestRegisterUserUsecase_Execute_UpsertsTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeRegisterUserRepository{}
	uc := usecase.NewRegisterUser(repository)

	input := usecase.RegisterUserInput{
		UserID:    uuid.New(),
		Username:  "ada-lovelace",
		CreatedAt: time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.UserID != input.UserID {
		t.Fatalf("Upsert() UserID = %v, want %v", repository.gotInput.UserID, input.UserID)
	}
}

func TestRegisterUserUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{})

	input := usecase.RegisterUserInput{Username: "ada-lovelace"}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestRegisterUserUsecase_Execute_ReturnsErrorWhenUsernameIsMissing(t *testing.T) {
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{})

	input := usecase.RegisterUserInput{UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when username is missing")
	}
}

func TestRegisterUserUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{err: wantErr})

	input := usecase.RegisterUserInput{UserID: uuid.New(), Username: "ada-lovelace"}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
