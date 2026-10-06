package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

type fakeUpdatePostRepository struct {
	version      int64
	found        bool
	getVersionEr error
	updateCalls  int
	gotInput     usecase.UpdatePostInput
	updateErr    error
}

func (f *fakeUpdatePostRepository) GetVersion(ctx context.Context, postID uuid.UUID) (int64, bool, error) {
	return f.version, f.found, f.getVersionEr
}

func (f *fakeUpdatePostRepository) Update(ctx context.Context, input usecase.UpdatePostInput) error {
	f.updateCalls++
	f.gotInput = input
	return f.updateErr
}

func validUpdatePostInput() usecase.UpdatePostInput {
	return usecase.UpdatePostInput{
		PostID:      uuid.New(),
		PostType:    "TEXT",
		Description: "updated description",
		Tags:        []string{"edited"},
	}
}

func TestUpdatePostUsecase_Execute_UpdatesThePostReadModelWithTheCurrentVersion(t *testing.T) {
	repository := &fakeUpdatePostRepository{version: 1, found: true}
	uc := usecase.NewUpdatePost(repository)

	input := validUpdatePostInput()
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.updateCalls != 1 {
		t.Fatalf("Update() calls = %d, want 1", repository.updateCalls)
	}
	if repository.gotInput.ExpectedVersion != 1 {
		t.Fatalf("Update() ExpectedVersion = %d, want 1 (from GetVersion)", repository.gotInput.ExpectedVersion)
	}
	if repository.gotInput.Description != input.Description {
		t.Fatalf("Update() Description = %q, want %q", repository.gotInput.Description, input.Description)
	}
}

func TestUpdatePostUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewUpdatePost(&fakeUpdatePostRepository{})

	input := validUpdatePostInput()
	input.PostID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestUpdatePostUsecase_Execute_ReturnsErrorWhenThePostHasNoProjectionYet(t *testing.T) {
	repository := &fakeUpdatePostRepository{found: false}
	uc := usecase.NewUpdatePost(repository)

	if err := uc.Execute(context.Background(), validUpdatePostInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error when the post was never projected")
	}
	if repository.updateCalls != 0 {
		t.Fatalf("Update() calls = %d, want 0 when GetVersion reports not found", repository.updateCalls)
	}
}

func TestUpdatePostUsecase_Execute_PropagatesTheGetVersionError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	uc := usecase.NewUpdatePost(&fakeUpdatePostRepository{getVersionEr: wantErr})

	if err := uc.Execute(context.Background(), validUpdatePostInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestUpdatePostUsecase_Execute_PropagatesTheUpdateError(t *testing.T) {
	wantErr := errors.New("version conflict")
	uc := usecase.NewUpdatePost(&fakeUpdatePostRepository{version: 1, found: true, updateErr: wantErr})

	if err := uc.Execute(context.Background(), validUpdatePostInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
