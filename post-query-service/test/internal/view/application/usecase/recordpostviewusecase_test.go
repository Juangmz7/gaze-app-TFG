package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
)

type fakeRecordPostViewRepository struct {
	inputs []usecase.RecordPostViewInput
	err    error
}

func (f *fakeRecordPostViewRepository) Record(_ context.Context, input usecase.RecordPostViewInput) error {
	f.inputs = append(f.inputs, input)
	return f.err
}

func TestRecordPostViewUsecase_Execute_RecordsTheView(t *testing.T) {
	repository := &fakeRecordPostViewRepository{}
	input := usecase.RecordPostViewInput{UserID: uuid.New(), PostID: uuid.New(), ViewedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)}

	if err := usecase.NewRecordPostView(repository).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repository.inputs) != 1 || repository.inputs[0] != input {
		t.Fatalf("Record() inputs = %v, want exactly %v", repository.inputs, input)
	}
}

func TestRecordPostViewUsecase_Execute_RejectsAnInvalidInputWithoutWriting(t *testing.T) {
	valid := usecase.RecordPostViewInput{UserID: uuid.New(), PostID: uuid.New(), ViewedAt: time.Now().UTC()}
	cases := map[string]func(*usecase.RecordPostViewInput){
		"no user":      func(input *usecase.RecordPostViewInput) { input.UserID = uuid.Nil },
		"no post":      func(input *usecase.RecordPostViewInput) { input.PostID = uuid.Nil },
		"no view time": func(input *usecase.RecordPostViewInput) { input.ViewedAt = time.Time{} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			repository := &fakeRecordPostViewRepository{}
			input := valid
			mutate(&input)

			if err := usecase.NewRecordPostView(repository).Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want a validation error")
			}
			if len(repository.inputs) != 0 {
				t.Fatalf("Record() calls = %d, want 0", len(repository.inputs))
			}
		})
	}
}

func TestRecordPostViewUsecase_Execute_PropagatesAWriteFailure(t *testing.T) {
	failure := errors.New("mongo down")
	repository := &fakeRecordPostViewRepository{err: failure}
	input := usecase.RecordPostViewInput{UserID: uuid.New(), PostID: uuid.New(), ViewedAt: time.Now().UTC()}

	if err := usecase.NewRecordPostView(repository).Execute(context.Background(), input); !errors.Is(err, failure) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, failure)
	}
}
