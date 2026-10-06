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
	insertCalls int
	gotInput    usecase.RegisterUserInput
	err         error
}

func (f *fakeRegisterUserRepository) Insert(ctx context.Context, input usecase.RegisterUserInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestRegisterUserUsecase_Execute_InsertsTheUserReadModelForAValidInput(t *testing.T) {
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

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.UserID != input.UserID {
		t.Fatalf("Insert() UserID = %v, want %v", repository.gotInput.UserID, input.UserID)
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
