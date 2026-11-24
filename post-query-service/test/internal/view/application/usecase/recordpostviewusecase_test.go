package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
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

type viewCountCall struct {
	postID uuid.UUID
	delta  int64
}

// fakeRecordPostViewCounter stands in for the posts repository's AdjustViewCount.
type fakeRecordPostViewCounter struct {
	calls    []viewCountCall
	notFound bool
	err      error
}

func (f *fakeRecordPostViewCounter) AdjustViewCount(_ context.Context, postID uuid.UUID, delta int64) (bool, error) {
	f.calls = append(f.calls, viewCountCall{postID: postID, delta: delta})
	if f.err != nil {
		return false, f.err
	}
	return !f.notFound, nil
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func validRecordPostViewInput() usecase.RecordPostViewInput {
	return usecase.RecordPostViewInput{UserID: uuid.New(), PostID: uuid.New(), ViewedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), CorrelationID: uuid.New()}
}

func TestRecordPostViewUsecase_Execute_RecordsTheView(t *testing.T) {
	repository := &fakeRecordPostViewRepository{}
	input := validRecordPostViewInput()

	if err := usecase.NewRecordPostView(repository, &fakeRecordPostViewCounter{}, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repository.inputs) != 1 || repository.inputs[0] != input {
		t.Fatalf("Record() inputs = %v, want exactly %v", repository.inputs, input)
	}
}

func TestRecordPostViewUsecase_Execute_IncrementsTheViewCountByOneWhenAPostViewIsRecorded(t *testing.T) {
	counter := &fakeRecordPostViewCounter{}
	input := validRecordPostViewInput()

	if err := usecase.NewRecordPostView(&fakeRecordPostViewRepository{}, counter, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(counter.calls) != 1 || counter.calls[0] != (viewCountCall{postID: input.PostID, delta: 1}) {
		t.Fatalf("AdjustViewCount() calls = %v, want exactly one +1 on post %s", counter.calls, input.PostID)
	}
}

func TestRecordPostViewUsecase_Execute_RecordsTheHistoryAndDoesNotFailWhenTheViewedPostIsNotProjected(t *testing.T) {
	repository := &fakeRecordPostViewRepository{}
	counter := &fakeRecordPostViewCounter{notFound: true}
	logs := &bytes.Buffer{}
	input := validRecordPostViewInput()

	if err := usecase.NewRecordPostView(repository, counter, slog.New(slog.NewTextHandler(logs, nil))).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil: a view of an unprojected post is not an error", err)
	}

	if len(repository.inputs) != 1 {
		t.Fatalf("Record() calls = %d, want the history row recorded", len(repository.inputs))
	}
	line := logs.String()
	for _, want := range []string{"level=WARN", "post view not counted", input.PostID.String(), input.UserID.String(), input.CorrelationID.String()} {
		if !strings.Contains(line, want) {
			t.Fatalf("log = %q, want it to contain %q", line, want)
		}
	}
}

func TestRecordPostViewUsecase_Execute_PropagatesAViewCountFailure(t *testing.T) {
	failure := errors.New("mongo down")

	err := usecase.NewRecordPostView(&fakeRecordPostViewRepository{}, &fakeRecordPostViewCounter{err: failure}, discardLogger()).Execute(context.Background(), validRecordPostViewInput())
	if !errors.Is(err, failure) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, failure)
	}
}

func TestRecordPostViewUsecase_Execute_RejectsAnInvalidInputWithoutWriting(t *testing.T) {
	valid := validRecordPostViewInput()
	cases := map[string]func(*usecase.RecordPostViewInput){
		"no user":      func(input *usecase.RecordPostViewInput) { input.UserID = uuid.Nil },
		"no post":      func(input *usecase.RecordPostViewInput) { input.PostID = uuid.Nil },
		"no view time": func(input *usecase.RecordPostViewInput) { input.ViewedAt = time.Time{} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			repository := &fakeRecordPostViewRepository{}
			counter := &fakeRecordPostViewCounter{}
			input := valid
			mutate(&input)

			if err := usecase.NewRecordPostView(repository, counter, discardLogger()).Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want a validation error")
			}
			if len(repository.inputs) != 0 || len(counter.calls) != 0 {
				t.Fatalf("Record() calls = %d, AdjustViewCount() calls = %d, want 0", len(repository.inputs), len(counter.calls))
			}
		})
	}
}

func TestRecordPostViewUsecase_Execute_PropagatesAWriteFailureWithoutCounting(t *testing.T) {
	failure := errors.New("mongo down")
	repository := &fakeRecordPostViewRepository{err: failure}
	counter := &fakeRecordPostViewCounter{}

	if err := usecase.NewRecordPostView(repository, counter, discardLogger()).Execute(context.Background(), validRecordPostViewInput()); !errors.Is(err, failure) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, failure)
	}
	if len(counter.calls) != 0 {
		t.Fatalf("AdjustViewCount() calls = %d, want 0 when the history write failed", len(counter.calls))
	}
}
