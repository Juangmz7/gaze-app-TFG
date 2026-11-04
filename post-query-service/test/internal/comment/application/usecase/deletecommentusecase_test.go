package usecase_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

type fakeDeleteCommentRepository struct {
	removed      bool
	err          error
	calls        int
	gotCommentID uuid.UUID
}

func (f *fakeDeleteCommentRepository) Delete(ctx context.Context, commentID uuid.UUID) (bool, error) {
	f.calls++
	f.gotCommentID = commentID
	return f.removed, f.err
}

type fakeDeleteCommentPostStats struct {
	notFound bool
	err      error
	deltas   []int64
	postIDs  []uuid.UUID
}

func (f *fakeDeleteCommentPostStats) AdjustCommentCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.deltas = append(f.deltas, delta)
	f.postIDs = append(f.postIDs, postID)
	return !f.notFound, f.err
}

func validDeleteCommentInput() usecase.DeleteCommentInput {
	return usecase.DeleteCommentInput{CommentID: uuid.New(), PostID: uuid.New()}
}

func testDeleteCommentLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestDeleteCommentUsecase_Execute_DecrementsTheCounterWhenADocumentWasReallyRemoved(t *testing.T) {
	repository := &fakeDeleteCommentRepository{removed: true}
	stats := &fakeDeleteCommentPostStats{}
	uc := usecase.NewDeleteComment(repository, stats, testDeleteCommentLogger())
	input := validDeleteCommentInput()

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.gotCommentID != input.CommentID {
		t.Fatalf("Delete() commentID = %v, want %v", repository.gotCommentID, input.CommentID)
	}
	if len(stats.deltas) != 1 || stats.deltas[0] != -1 {
		t.Fatalf("AdjustCommentCount() deltas = %v, want [-1]", stats.deltas)
	}
	if stats.postIDs[0] != input.PostID {
		t.Fatalf("AdjustCommentCount() postID = %v, want %v", stats.postIDs[0], input.PostID)
	}
}

func TestDeleteCommentUsecase_Execute_LeavesTheCounterAloneWhenNothingWasRemoved(t *testing.T) {
	stats := &fakeDeleteCommentPostStats{}
	uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{removed: false}, stats, testDeleteCommentLogger())

	if err := uc.Execute(context.Background(), validDeleteCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 0 {
		t.Fatalf("AdjustCommentCount() deltas = %v, want none when nothing was removed", stats.deltas)
	}
}

// A document can only exist once its post was projected, so a missing post here
// means the post was deleted. A retry could never succeed, so this acks.
func TestDeleteCommentUsecase_Execute_AcksWhenThePostWasDeletedInTheMeantime(t *testing.T) {
	uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{removed: true}, &fakeDeleteCommentPostStats{notFound: true}, testDeleteCommentLogger())

	if err := uc.Execute(context.Background(), validDeleteCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}
}

func TestDeleteCommentUsecase_Execute_PropagatesDependencyErrors(t *testing.T) {
	wantErr := errors.New("mongo write failed")

	t.Run("repository", func(t *testing.T) {
		uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{err: wantErr}, &fakeDeleteCommentPostStats{}, testDeleteCommentLogger())
		if err := uc.Execute(context.Background(), validDeleteCommentInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("post stats", func(t *testing.T) {
		uc := usecase.NewDeleteComment(&fakeDeleteCommentRepository{removed: true}, &fakeDeleteCommentPostStats{err: wantErr}, testDeleteCommentLogger())
		if err := uc.Execute(context.Background(), validDeleteCommentInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestDeleteCommentUsecase_Execute_ReturnsAnErrorWhenARequiredIDIsMissing(t *testing.T) {
	tests := []struct {
		name  string
		clear func(*usecase.DeleteCommentInput)
	}{
		{name: "comment id", clear: func(input *usecase.DeleteCommentInput) { input.CommentID = uuid.Nil }},
		{name: "post id", clear: func(input *usecase.DeleteCommentInput) { input.PostID = uuid.Nil }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeDeleteCommentRepository{removed: true}
			uc := usecase.NewDeleteComment(repository, &fakeDeleteCommentPostStats{}, testDeleteCommentLogger())
			input := validDeleteCommentInput()
			tt.clear(&input)

			if err := uc.Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want an error")
			}
			if repository.calls != 0 {
				t.Fatal("the repository was called for an invalid input")
			}
		})
	}
}
