package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

type fakeCloseCollabRepository struct {
	version      int64
	found        bool
	getVersionEr error
	updateCalls  int
	gotInput     usecase.CloseCollabInput
	updateErr    error
}

func (f *fakeCloseCollabRepository) GetVersion(ctx context.Context, collabID uuid.UUID) (int64, bool, error) {
	return f.version, f.found, f.getVersionEr
}

func (f *fakeCloseCollabRepository) Update(ctx context.Context, input usecase.CloseCollabInput) error {
	f.updateCalls++
	f.gotInput = input
	return f.updateErr
}

func validCloseCollabInput() usecase.CloseCollabInput {
	return usecase.CloseCollabInput{
		CollabID:  uuid.New(),
		Title:     "my collab",
		CreatedBy: uuid.New(),
		ClosedBy:  uuid.New(),
		Status:    "CLOSED",
	}
}

func TestCloseCollabUsecase_Execute_UpdatesTheCollabReadModelWithTheCurrentVersion(t *testing.T) {
	repository := &fakeCloseCollabRepository{version: 1, found: true}
	uc := usecase.NewCloseCollab(repository)

	input := validCloseCollabInput()
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.updateCalls != 1 {
		t.Fatalf("Update() calls = %d, want 1", repository.updateCalls)
	}
	if repository.gotInput.ExpectedVersion != 1 {
		t.Fatalf("Update() ExpectedVersion = %d, want 1 (from GetVersion)", repository.gotInput.ExpectedVersion)
	}
	if repository.gotInput.Status != input.Status {
		t.Fatalf("Update() Status = %q, want %q", repository.gotInput.Status, input.Status)
	}
}

func TestCloseCollabUsecase_Execute_ReturnsErrorWhenCollabIDIsMissing(t *testing.T) {
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{})

	input := validCloseCollabInput()
	input.CollabID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestCloseCollabUsecase_Execute_ReturnsErrorWhenCreatedByIsMissing(t *testing.T) {
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{})

	input := validCloseCollabInput()
	input.CreatedBy = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when created by is missing")
	}
}

func TestCloseCollabUsecase_Execute_ReturnsErrorWhenClosedByIsMissing(t *testing.T) {
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{})

	input := validCloseCollabInput()
	input.ClosedBy = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when closed by is missing")
	}
}

func TestCloseCollabUsecase_Execute_ReturnsErrorWhenStatusIsMissing(t *testing.T) {
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{})

	input := validCloseCollabInput()
	input.Status = ""

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when status is missing")
	}
}

func TestCloseCollabUsecase_Execute_ReturnsErrorWhenTheCollabHasNoProjectionYet(t *testing.T) {
	repository := &fakeCloseCollabRepository{found: false}
	uc := usecase.NewCloseCollab(repository)

	if err := uc.Execute(context.Background(), validCloseCollabInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error when the collab was never projected (collab-opened not seen yet)")
	}
	if repository.updateCalls != 0 {
		t.Fatalf("Update() calls = %d, want 0 when GetVersion reports not found", repository.updateCalls)
	}
}

func TestCloseCollabUsecase_Execute_PropagatesTheGetVersionError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{getVersionEr: wantErr})

	if err := uc.Execute(context.Background(), validCloseCollabInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestCloseCollabUsecase_Execute_PropagatesTheUpdateError(t *testing.T) {
	wantErr := errors.New("version conflict")
	uc := usecase.NewCloseCollab(&fakeCloseCollabRepository{version: 1, found: true, updateErr: wantErr})

	if err := uc.Execute(context.Background(), validCloseCollabInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
