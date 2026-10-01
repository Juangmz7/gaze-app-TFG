package registeruser_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase/registeruser"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    registeruser.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input registeruser.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := registeruser.New(repository)

	input := registeruser.Input{
		UserID:    uuid.New(),
		Username:  "ada-lovelace",
		CreatedAt: time.Now().UTC(),
	}

	if err := usecase.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.UserID != input.UserID {
		t.Fatalf("Upsert() UserID = %v, want %v", repository.gotInput.UserID, input.UserID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	usecase := registeruser.New(&fakeRepository{})

	input := registeruser.Input{Username: "ada-lovelace"}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUsernameIsMissing(t *testing.T) {
	usecase := registeruser.New(&fakeRepository{})

	input := registeruser.Input{UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when username is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	usecase := registeruser.New(&fakeRepository{err: wantErr})

	input := registeruser.Input{UserID: uuid.New(), Username: "ada-lovelace"}

	if err := usecase.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
