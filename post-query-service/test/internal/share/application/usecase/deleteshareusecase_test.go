package usecase_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

type fakeDeleteShareRepository struct {
	removed   bool
	err       error
	calls     int
	gotPostID uuid.UUID
	gotUserID uuid.UUID
}

func (f *fakeDeleteShareRepository) DeleteByPostAndUser(ctx context.Context, postID, userID uuid.UUID) (bool, error) {
	f.calls++
	f.gotPostID = postID
	f.gotUserID = userID
	return f.removed, f.err
}

type fakeDeleteSharePostStats struct {
	notFound bool
	err      error
	deltas   []int64
	postIDs  []uuid.UUID
}

func (f *fakeDeleteSharePostStats) AdjustShareCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.deltas = append(f.deltas, delta)
	f.postIDs = append(f.postIDs, postID)
	return !f.notFound, f.err
}

func validDeleteShareInput() usecase.DeleteShareInput {
	return usecase.DeleteShareInput{PostID: uuid.New(), UserID: uuid.New()}
}

func testDeleteShareLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestDeleteShareUsecase_Execute_DecrementsTheCounterWhenADocumentWasReallyRemoved(t *testing.T) {
	repository := &fakeDeleteShareRepository{removed: true}
	stats := &fakeDeleteSharePostStats{}
	uc := usecase.NewDeleteShare(repository, stats, testDeleteShareLogger())
	input := validDeleteShareInput()

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.gotPostID != input.PostID || repository.gotUserID != input.UserID {
		t.Fatalf("DeleteByPostAndUser() postID/userID = %v/%v, want %v/%v", repository.gotPostID, repository.gotUserID, input.PostID, input.UserID)
	}
	if len(stats.deltas) != 1 || stats.deltas[0] != -1 {
		t.Fatalf("AdjustShareCount() deltas = %v, want [-1]", stats.deltas)
	}
	if stats.postIDs[0] != input.PostID {
		t.Fatalf("AdjustShareCount() postID = %v, want %v", stats.postIDs[0], input.PostID)
	}
}

func TestDeleteShareUsecase_Execute_LeavesTheCounterAloneWhenNothingWasRemoved(t *testing.T) {
	stats := &fakeDeleteSharePostStats{}
	uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{removed: false}, stats, testDeleteShareLogger())

	if err := uc.Execute(context.Background(), validDeleteShareInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 0 {
		t.Fatalf("AdjustShareCount() deltas = %v, want none when nothing was removed", stats.deltas)
	}
}

// A document can only exist once its post was projected, so a missing post here
// means the post was deleted. A retry could never succeed, so this acks.
func TestDeleteShareUsecase_Execute_AcksWhenThePostWasDeletedInTheMeantime(t *testing.T) {
	uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{removed: true}, &fakeDeleteSharePostStats{notFound: true}, testDeleteShareLogger())

	if err := uc.Execute(context.Background(), validDeleteShareInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}
}

func TestDeleteShareUsecase_Execute_PropagatesDependencyErrors(t *testing.T) {
	wantErr := errors.New("mongo write failed")

	t.Run("repository", func(t *testing.T) {
		uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{err: wantErr}, &fakeDeleteSharePostStats{}, testDeleteShareLogger())
		if err := uc.Execute(context.Background(), validDeleteShareInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("post stats", func(t *testing.T) {
		uc := usecase.NewDeleteShare(&fakeDeleteShareRepository{removed: true}, &fakeDeleteSharePostStats{err: wantErr}, testDeleteShareLogger())
		if err := uc.Execute(context.Background(), validDeleteShareInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestDeleteShareUsecase_Execute_ReturnsAnErrorWhenARequiredIDIsMissing(t *testing.T) {
	tests := []struct {
		name  string
		clear func(*usecase.DeleteShareInput)
	}{
		{name: "post id", clear: func(input *usecase.DeleteShareInput) { input.PostID = uuid.Nil }},
		{name: "user id", clear: func(input *usecase.DeleteShareInput) { input.UserID = uuid.Nil }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeDeleteShareRepository{removed: true}
			uc := usecase.NewDeleteShare(repository, &fakeDeleteSharePostStats{}, testDeleteShareLogger())
			input := validDeleteShareInput()
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
