package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

type fakeDeleteCommentRepository struct {
	deleteCalls  int
	gotCommentID uuid.UUID
	err          error
}

func (f *fakeDeleteCommentRepository) Delete(ctx context.Context, commentID uuid.UUID) error {
	f.deleteCalls++
	f.gotCommentID = commentID
	return f.err
}

func TestDeleteCommentUsecase_Execute_DeletesTheCommentReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeleteCommentRepository{}
	uc := usecase.NewDeleteComment(repository)

	commentID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeleteCommentInput{CommentID: commentID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotCommentID != commentID {
		t.Fatalf("Delete() commentID = %v, want %v", repository.gotCommentID, commentID)
	}
}

func TestDeleteCommentUsecase_Execute_ReturnsErrorWhenCommentIDIsMissing(t *testing.T) {
	uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{})

	if err := uc.Execute(context.Background(), usecase.DeleteCommentInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when comment id is missing")
	}
}

func TestDeleteCommentUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeleteCommentInput{CommentID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
