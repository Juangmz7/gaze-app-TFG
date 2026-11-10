package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/application/usecase"
)

type fakeRecordLikeRepository struct {
	inserted  bool
	err       error
	insertLog []usecase.RecordLikeInput
}

func (f *fakeRecordLikeRepository) Insert(ctx context.Context, input usecase.RecordLikeInput) (bool, error) {
	f.insertLog = append(f.insertLog, input)
	return f.inserted, f.err
}

type fakeRecordLikePostStats struct {
	notFound bool
	err      error
	deltas   []int64
	postIDs  []uuid.UUID
}

func (f *fakeRecordLikePostStats) AdjustLikeCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.deltas = append(f.deltas, delta)
	f.postIDs = append(f.postIDs, postID)
	return !f.notFound, f.err
}

func validRecordLikeInput() usecase.RecordLikeInput {
	return usecase.RecordLikeInput{
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}
}

func TestRecordLikeUsecase_Execute_IncrementsTheCounterWhenADocumentWasReallyInserted(t *testing.T) {
	repository := &fakeRecordLikeRepository{inserted: true}
	stats := &fakeRecordLikePostStats{}
	uc := usecase.NewRecordLike(repository, stats)
	input := validRecordLikeInput()

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 1 || stats.deltas[0] != 1 {
		t.Fatalf("AdjustLikeCount() deltas = %v, want [1]", stats.deltas)
	}
	if stats.postIDs[0] != input.PostID {
		t.Fatalf("AdjustLikeCount() postID = %v, want %v", stats.postIDs[0], input.PostID)
	}
}

func TestRecordLikeUsecase_Execute_LeavesTheCounterAloneWhenTheDocumentAlreadyExisted(t *testing.T) {
	stats := &fakeRecordLikePostStats{}
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{inserted: false}, stats)

	if err := uc.Execute(context.Background(), validRecordLikeInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 0 {
		t.Fatalf("AdjustLikeCount() deltas = %v, want none when nothing was inserted", stats.deltas)
	}
}

func TestRecordLikeUsecase_Execute_ReturnsARetryableErrorWhenThePostIsNotProjectedYet(t *testing.T) {
	uc := usecase.NewRecordLike(&fakeRecordLikeRepository{inserted: true}, &fakeRecordLikePostStats{notFound: true})

	err := uc.Execute(context.Background(), validRecordLikeInput())
	if err == nil {
		t.Fatal("Execute() error = nil, want an error so the transaction aborts and the message is redelivered")
	}
}

func TestRecordLikeUsecase_Execute_PropagatesDependencyErrors(t *testing.T) {
	wantErr := errors.New("mongo write failed")

	t.Run("repository", func(t *testing.T) {
		uc := usecase.NewRecordLike(&fakeRecordLikeRepository{err: wantErr}, &fakeRecordLikePostStats{})
		if err := uc.Execute(context.Background(), validRecordLikeInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("post stats", func(t *testing.T) {
		uc := usecase.NewRecordLike(&fakeRecordLikeRepository{inserted: true}, &fakeRecordLikePostStats{err: wantErr})
		if err := uc.Execute(context.Background(), validRecordLikeInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestRecordLikeUsecase_Execute_ReturnsAnErrorWhenARequiredIDIsMissing(t *testing.T) {
	tests := []struct {
		name  string
		clear func(*usecase.RecordLikeInput)
	}{
		{name: "post id", clear: func(input *usecase.RecordLikeInput) { input.PostID = uuid.Nil }},
		{name: "user id", clear: func(input *usecase.RecordLikeInput) { input.UserID = uuid.Nil }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeRecordLikeRepository{inserted: true}
			uc := usecase.NewRecordLike(repository, &fakeRecordLikePostStats{})
			input := validRecordLikeInput()
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
