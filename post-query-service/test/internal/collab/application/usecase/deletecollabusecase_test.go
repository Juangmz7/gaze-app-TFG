package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

type fakeDeleteCollabRepository struct {
	deleteCalls int
	gotCollabID uuid.UUID
	err         error
}

func (f *fakeDeleteCollabRepository) Delete(ctx context.Context, collabID uuid.UUID) error {
	f.deleteCalls++
	f.gotCollabID = collabID
	return f.err
}

func TestDeleteCollabUsecase_Execute_DeletesTheCollabReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteCollabRepository{}
	uc := usecase.NewDeleteCollab(repository)

	collabID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeleteCollabInput{CollabID: collabID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotCollabID != collabID {
		t.Fatalf("Delete() collabID = %v, want %v", repository.gotCollabID, collabID)
	}
}

func TestDeleteCollabUsecase_Execute_ReturnsErrorWhenCollabIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteCollab(&fakeDeleteCollabRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteCollabInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestDeleteCollabUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteCollab(&fakeDeleteCollabRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeleteCollabInput{CollabID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
