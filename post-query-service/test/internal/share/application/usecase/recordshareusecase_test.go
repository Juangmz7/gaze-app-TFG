package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

type fakeRecordShareRepository struct {
	insertCalls int
	gotInput    usecase.RecordShareInput
	err         error
}

func (f *fakeRecordShareRepository) Insert(ctx context.Context, input usecase.RecordShareInput) error {
	f.insertCalls++
	f.gotInput = input
	return f.err
}

func TestRecordShareUsecase_Execute_InsertsTheShareReadModelForAValidInput(t *testing.T) {
	repository := &fakeRecordShareRepository{}
	uc := usecase.NewRecordShare(repository)

	input := usecase.RecordShareInput{
		ShareID:   uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.insertCalls != 1 {
		t.Fatalf("Insert() calls = %d, want 1", repository.insertCalls)
	}
	if repository.gotInput.ShareID != input.ShareID {
		t.Fatalf("Insert() ShareID = %v, want %v", repository.gotInput.ShareID, input.ShareID)
	}
}

func TestRecordShareUsecase_Execute_ReturnsErrorWhenShareIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{})

	input := usecase.RecordShareInput{PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when share id is missing")
	}
}

func TestRecordShareUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{})

	input := usecase.RecordShareInput{ShareID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestRecordShareUsecase_Execute_ReturnsErrorWhenUserIDIsMissing(t *testing.T) {
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{})

	input := usecase.RecordShareInput{ShareID: uuid.New(), PostID: uuid.New()}

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when user id is missing")
	}
}

func TestRecordShareUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{err: wantErr})

	input := usecase.RecordShareInput{ShareID: uuid.New(), PostID: uuid.New(), UserID: uuid.New()}

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
