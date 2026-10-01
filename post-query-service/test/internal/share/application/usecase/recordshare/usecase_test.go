package recordshare_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase/recordshare"
)

type fakeRepository struct {
	upsertCalls int
	gotInput    recordshare.Input
	err         error
}

func (f *fakeRepository) Upsert(ctx context.Context, input recordshare.Input) error {
	f.upsertCalls++
	f.gotInput = input
	return f.err
}

func TestUsecase_Execute_UpsertsTheShareReadModelForAValidInput(t *testing.T) {
	repository := &fakeRepository{}
	usecase := recordshare.New(repository)

	input := recordshare.Input{
		ShareID:   uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := usecase.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.upsertCalls != 1 {
		t.Fatalf("Upsert() calls = %d, want 1", repository.upsertCalls)
	}
	if repository.gotInput.ShareID != input.ShareID {
		t.Fatalf("Upsert() ShareID = %v, want %v", repository.gotInput.ShareID, input.ShareID)
	}
}

func TestUsecase_Execute_ReturnsErrorWhenShareIDIsMissing(t *testing.T) {
	usecase := recordshare.New(&fakeRepository{})

	input := recordshare.Input{PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when share id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	usecase := recordshare.New(&fakeRepository{})

	input := recordshare.Input{ShareID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	usecase := recordshare.New(&fakeRepository{})

	input := recordshare.Input{ShareID: uuid.New(), PostID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	usecase := recordshare.New(&fakeRepository{err: wantErr})

	input := recordshare.Input{ShareID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := usecase.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
