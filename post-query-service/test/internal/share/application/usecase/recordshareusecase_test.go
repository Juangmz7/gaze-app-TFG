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
	inserted  bool
	err       error
	insertLog []usecase.RecordShareInput
}

func (f *fakeRecordShareRepository) Insert(ctx context.Context, input usecase.RecordShareInput) (bool, error) {
	f.insertLog = append(f.insertLog, input)
	return f.inserted, f.err
}

type fakeRecordSharePostStats struct {
	notFound bool
	err      error
	deltas   []int64
	postIDs  []uuid.UUID
}

func (f *fakeRecordSharePostStats) AdjustShareCount(ctx context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.deltas = append(f.deltas, delta)
	f.postIDs = append(f.postIDs, postID)
	return !f.notFound, f.err
}

func validRecordShareInput() usecase.RecordShareInput {
	return usecase.RecordShareInput{
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		CreatedAt: time.Now().UTC(),
	}
}

func TestRecordShareUsecase_Execute_IncrementsTheCounterWhenADocumentWasReallyInserted(t *testing.T) {
	repository := &fakeRecordShareRepository{inserted: true}
	stats := &fakeRecordSharePostStats{}
	uc := usecase.NewRecordShare(repository, stats)
	input := validRecordShareInput()

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 1 || stats.deltas[0] != 1 {
		t.Fatalf("AdjustShareCount() deltas = %v, want [1]", stats.deltas)
	}
	if stats.postIDs[0] != input.PostID {
		t.Fatalf("AdjustShareCount() postID = %v, want %v", stats.postIDs[0], input.PostID)
	}
}

func TestRecordShareUsecase_Execute_LeavesTheCounterAloneWhenTheDocumentAlreadyExisted(t *testing.T) {
	stats := &fakeRecordSharePostStats{}
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{inserted: false}, stats)

	if err := uc.Execute(context.Background(), validRecordShareInput()); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(stats.deltas) != 0 {
		t.Fatalf("AdjustShareCount() deltas = %v, want none when nothing was inserted", stats.deltas)
	}
}

func TestRecordShareUsecase_Execute_ReturnsARetryableErrorWhenThePostIsNotProjectedYet(t *testing.T) {
	uc := usecase.NewRecordShare(&fakeRecordShareRepository{inserted: true}, &fakeRecordSharePostStats{notFound: true})

	err := uc.Execute(context.Background(), validRecordShareInput())
	if err == nil {
		t.Fatal("Execute() error = nil, want an error so the transaction aborts and the message is redelivered")
	}
}

func TestRecordShareUsecase_Execute_PropagatesDependencyErrors(t *testing.T) {
	wantErr := errors.New("mongo write failed")

	t.Run("repository", func(t *testing.T) {
		uc := usecase.NewRecordShare(&fakeRecordShareRepository{err: wantErr}, &fakeRecordSharePostStats{})
		if err := uc.Execute(context.Background(), validRecordShareInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("post stats", func(t *testing.T) {
		uc := usecase.NewRecordShare(&fakeRecordShareRepository{inserted: true}, &fakeRecordSharePostStats{err: wantErr})
		if err := uc.Execute(context.Background(), validRecordShareInput()); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestRecordShareUsecase_Execute_ReturnsAnErrorWhenARequiredIDIsMissing(t *testing.T) {
	tests := []struct {
		name  string
		clear func(*usecase.RecordShareInput)
	}{
		{name: "post id", clear: func(input *usecase.RecordShareInput) { input.PostID = uuid.Nil }},
		{name: "user id", clear: func(input *usecase.RecordShareInput) { input.UserID = uuid.Nil }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeRecordShareRepository{inserted: true}
			uc := usecase.NewRecordShare(repository, &fakeRecordSharePostStats{})
			input := validRecordShareInput()
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
