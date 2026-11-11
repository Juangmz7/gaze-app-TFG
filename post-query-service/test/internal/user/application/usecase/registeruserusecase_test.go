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

type fakeRegisterUserPrefetcher struct {
	gotUserIDs       []uuid.UUID
	gotCorrelationID string
	err              error
}

func (f *fakeRegisterUserPrefetcher) RequestUserRegisteredPrefetch(_ context.Context, userID uuid.UUID, correlationID string) error {
	f.gotUserIDs = append(f.gotUserIDs, userID)
	f.gotCorrelationID = correlationID
	return f.err
}

func TestRegisterUserUsecase_Execute_RequestsThePrefetchAfterTheProjection(t *testing.T) {
	prefetcher := &fakeRegisterUserPrefetcher{}
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{}, prefetcher)
	input := usecase.RegisterUserInput{UserID: uuid.New(), Username: "ada-lovelace", CorrelationID: "corr-1"}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(prefetcher.gotUserIDs) != 1 || prefetcher.gotUserIDs[0] != input.UserID || prefetcher.gotCorrelationID != "corr-1" {
		t.Fatalf("prefetch = %v/%s, want one for %v/corr-1", prefetcher.gotUserIDs, prefetcher.gotCorrelationID, input.UserID)
	}
}

func TestRegisterUserUsecase_Execute_RequestsNoPrefetchWhenTheProjectionFails(t *testing.T) {
	prefetcher := &fakeRegisterUserPrefetcher{}
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{err: errors.New("mongo failed")}, prefetcher)

	if err := uc.Execute(context.Background(), usecase.RegisterUserInput{UserID: uuid.New(), Username: "ada-lovelace"}); err == nil {
		t.Fatal("Execute() error = nil, want the projection failure")
	}
	if len(prefetcher.gotUserIDs) != 0 {
		t.Fatalf("prefetch = %v, want none", prefetcher.gotUserIDs)
	}
}

func TestRegisterUserUsecase_Execute_ReturnsAFailedPrefetchSoTheEventIsRedelivered(t *testing.T) {
	wantErr := errors.New("mongo failed")
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{}, &fakeRegisterUserPrefetcher{err: wantErr})

	err := uc.Execute(context.Background(), usecase.RegisterUserInput{UserID: uuid.New(), Username: "ada-lovelace"})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestRegisterUserUsecase_Execute_InsertsTheUserReadModelForAValidInput(t *testing.T) {
	repository := &fakeRegisterUserRepository{}
	uc := usecase.NewRegisterUser(repository, &fakeRegisterUserPrefetcher{})

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
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{}, &fakeRegisterUserPrefetcher{})

	input := usecase.RegisterUserInput{Username: "ada-lovelace"}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestRegisterUserUsecase_Execute_ReturnsErrorWhenUsernameIsMissing(t *testing.T) {
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{}, &fakeRegisterUserPrefetcher{})

	input := usecase.RegisterUserInput{UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when username is missing")
	}
}

func TestRegisterUserUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewRegisterUser(&fakeRegisterUserRepository{err: wantErr}, &fakeRegisterUserPrefetcher{})

	input := usecase.RegisterUserInput{UserID: uuid.New(), Username: "ada-lovelace"}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
