package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

type fakeRecordCommentRepository struct {
	inserted  bool
	err       error
	insertLog []usecase.RecordCommentInput
}

func (f *fakeRecordCommentRepository) Insert(ctx context.Context, input usecase.RecordCommentInput) (bool, error) {
	f.insertLog = append(f.insertLog, input)
	return f.inserted, f.err
}

type fakeRecordCommentPostStats struct {
	notFound bool
	err      error
	deltas   []int64
	postIDs  []uuid.UUID
}

func (f *fakeRecordCommentPostStats) AdjustCommentCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.deltas = append(f.deltas, delta)
	f.postIDs = append(f.postIDs, postID)
	return !f.notFound, f.err
}

func validRecordCommentInput() usecase.RecordCommentInput {
	return usecase.RecordCommentInput{
		CommentID: uuid.New(),
		Content:   "Lovely light in this one",
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}
}

func TestRecordCommentUsecase_Execute_IncrementsTheCounterWhenADocumentWasReallyInserted(t *testing.T) {
	repository := &fakeRecordCommentRepository{inserted: true}
	stats := &fakeRecordCommentPostStats{}
	uc := usecase.NewRecordComment(repository, stats)
	input := validRecordCommentInput()

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 1 || stats.deltas[0] != 1 {
		t.Fatalf("AdjustCommentCount() deltas = %v, want [1]", stats.deltas)
	}
	if stats.postIDs[0] != input.PostID {
		t.Fatalf("AdjustCommentCount() postID = %v, want %v", stats.postIDs[0], input.PostID)
	}
}

func TestRecordCommentUsecase_Execute_LeavesTheCounterAloneWhenTheDocumentAlreadyExisted(t *testing.T) {
	stats := &fakeRecordCommentPostStats{}
	uc := usecase.NewRecordComment(&fakeRecordCommentRepository{inserted: false}, stats)

	if err := uc.Execute(context.Background(), validRecordCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 0 {
		t.Fatalf("AdjustCommentCount() deltas = %v, want none when nothing was inserted", stats.deltas)
	}
}

func TestRecordCommentUsecase_Execute_ReturnsARetryableErrorWhenThePostIsNotProjectedYet(t *testing.T) {
	uc := usecase.NewRecordComment(&fakeRecordCommentRepository{inserted: true}, &fakeRecordCommentPostStats{notFound: true})

	err := uc.Execute(context.Background(), validRecordCommentInput())
	if err == nil {
		t.Fatal("Execute() error = nil, want an error so the transaction aborts and the message is redelivered")
	}
}

func TestRecordCommentUsecase_Execute_PropagatesDependencyErrors(t *testing.T) {
	wantErr := errors.New("mongo write failed")

	t.Run("repository", func(t *testing.T) {
		uc := usecase.NewRecordComment(&fakeRecordCommentRepository{err: wantErr}, &fakeRecordCommentPostStats{})
		if err := uc.Execute(context.Background(), validRecordCommentInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("post stats", func(t *testing.T) {
		uc := usecase.NewRecordComment(&fakeRecordCommentRepository{inserted: true}, &fakeRecordCommentPostStats{err: wantErr})
		if err := uc.Execute(context.Background(), validRecordCommentInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestRecordCommentUsecase_Execute_ReturnsAnErrorWhenARequiredIDIsMissing(t *testing.T) {
	tests := []struct {
		name  string
		clear func(*usecase.RecordCommentInput)
	}{
		{name: "comment id", clear: func(input *usecase.RecordCommentInput) { input.CommentID = uuid.Nil }},
		{name: "post id", clear: func(input *usecase.RecordCommentInput) { input.PostID = uuid.Nil }},
		{name: "user id", clear: func(input *usecase.RecordCommentInput) { input.UserID = uuid.Nil }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeRecordCommentRepository{inserted: true}
			uc := usecase.NewRecordComment(repository, &fakeRecordCommentPostStats{})
			input := validRecordCommentInput()
			tt.clear(&input)

			if err := uc.Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want an error")
			}
			if len(repository.insertLog) != 0 {
				t.Fatal("Insert() was called for an invalid input")
			}
		})
	}
}
