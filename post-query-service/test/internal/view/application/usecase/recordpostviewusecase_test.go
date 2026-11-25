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

// fakeRecordPostViewRepository stands in for the history repository: it records what
// it was asked and answers decision (ViewCounted when unset).
type fakeRecordPostViewRepository struct {
	inputs   []usecase.RecordPostViewHistory
	decision usecase.ViewCountDecision
	err      error
}

func (f *fakeRecordPostViewRepository) Record(_ context.Context, view usecase.RecordPostViewHistory) (usecase.ViewCountDecision, error) {
	f.inputs = append(f.inputs, view)
	if f.err != nil {
		return 0, f.err
	}
	if f.decision == 0 {
		return usecase.ViewCounted, nil
	}
	return f.decision, nil
}

// testDailyLimit is the production default of VIEW_COUNT_DAILY_LIMIT_PER_USER.
const testDailyLimit = 5

func newRecordPostView(repository usecase.RecordPostViewRepository, counter usecase.RecordPostViewCounter, logger *slog.Logger) *usecase.RecordPostViewUsecase {
	return usecase.NewRecordPostView(repository, counter, testDailyLimit, logger)
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

	if err := newRecordPostView(repository, &fakeRecordPostViewCounter{}, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	want := usecase.RecordPostViewHistory{UserID: input.UserID, PostID: input.PostID, ViewedAt: input.ViewedAt, Day: "2026-03-01", DailyLimit: testDailyLimit}
	if len(repository.inputs) != 1 || repository.inputs[0] != want {
		t.Fatalf("Record() inputs = %v, want exactly %v", repository.inputs, want)
	}
}

func TestRecordPostViewUsecase_Execute_IncrementsTheViewCountByOneWhenAPostViewIsRecorded(t *testing.T) {
	counter := &fakeRecordPostViewCounter{}
	input := validRecordPostViewInput()

	if err := newRecordPostView(&fakeRecordPostViewRepository{}, counter, discardLogger()).Execute(context.Background(), input); err != nil {
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

	if err := newRecordPostView(repository, counter, slog.New(slog.NewTextHandler(logs, nil))).Execute(context.Background(), input); err != nil {
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

	err := newRecordPostView(&fakeRecordPostViewRepository{}, &fakeRecordPostViewCounter{err: failure}, discardLogger()).Execute(context.Background(), validRecordPostViewInput())
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

			if err := newRecordPostView(repository, counter, discardLogger()).Execute(context.Background(), input); err == nil {
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

	if err := newRecordPostView(repository, counter, discardLogger()).Execute(context.Background(), validRecordPostViewInput()); !errors.Is(err, failure) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, failure)
	}
	if len(counter.calls) != 0 {
		t.Fatalf("AdjustViewCount() calls = %d, want 0 when the history write failed", len(counter.calls))
	}
}

func TestRecordPostViewUsecase_Execute_ShouldIncrementTheViewCountWhenTheHistoryReportsTheViewAsCounted(t *testing.T) {
	counter := &fakeRecordPostViewCounter{}
	input := validRecordPostViewInput()

	if err := newRecordPostView(&fakeRecordPostViewRepository{decision: usecase.ViewCounted}, counter, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(counter.calls) != 1 || counter.calls[0] != (viewCountCall{postID: input.PostID, delta: 1}) {
		t.Fatalf("AdjustViewCount() calls = %v, want exactly one +1 on post %s", counter.calls, input.PostID)
	}
}

func TestRecordPostViewUsecase_Execute_ShouldNotIncrementTheViewCountWhenTheDailyLimitIsReached(t *testing.T) {
	for name, decision := range map[string]usecase.ViewCountDecision{
		"daily limit reached":     usecase.ViewOverDailyLimit,
		"late view of a past day": usecase.ViewFromPastDay,
	} {
		t.Run(name, func(t *testing.T) {
			counter := &fakeRecordPostViewCounter{}
			logs := &bytes.Buffer{}
			logger := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
			input := validRecordPostViewInput()

			if err := newRecordPostView(&fakeRecordPostViewRepository{decision: decision}, counter, logger).Execute(context.Background(), input); err != nil {
				t.Fatalf("Execute() error = %v, want nil: a view that is not counted is not an error", err)
			}

			if len(counter.calls) != 0 {
				t.Fatalf("AdjustViewCount() calls = %v, want none", counter.calls)
			}
			for _, want := range []string{"level=DEBUG", "post view not counted", input.PostID.String(), input.UserID.String(), input.CorrelationID.String()} {
				if !strings.Contains(logs.String(), want) {
					t.Fatalf("log = %q, want it to contain %q", logs.String(), want)
				}
			}
		})
	}
}

func TestRecordPostViewUsecase_Execute_ShouldRecordTheHistoryEvenWhenTheViewIsNotCounted(t *testing.T) {
	repository := &fakeRecordPostViewRepository{decision: usecase.ViewOverDailyLimit}
	input := validRecordPostViewInput()

	if err := newRecordPostView(repository, &fakeRecordPostViewCounter{}, discardLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repository.inputs) != 1 || repository.inputs[0].ViewedAt != input.ViewedAt || repository.inputs[0].PostID != input.PostID {
		t.Fatalf("Record() inputs = %v, want the view recorded in the history", repository.inputs)
	}
}

func TestRecordPostViewUsecase_Execute_ShouldPassTheEventsServerTimestampDayInUTCToTheRepository(t *testing.T) {
	madrid := time.FixedZone("UTC+2", 2*60*60)
	cases := map[string]struct {
		viewedAt time.Time
		wantDay  string
	}{
		// 01:30 at UTC+2 is still the previous day in UTC.
		"after local midnight":   {viewedAt: time.Date(2026, 3, 2, 1, 30, 0, 0, madrid), wantDay: "2026-03-01"},
		"last instant of a day":  {viewedAt: time.Date(2026, 3, 1, 23, 59, 59, 999999999, time.UTC), wantDay: "2026-03-01"},
		"first instant of a day": {viewedAt: time.Date(2026, 3, 2, 0, 0, 0, 0, time.UTC), wantDay: "2026-03-02"},
		// Long after the consumer's clock moved on: the view's own day counts.
		"delayed delivery": {viewedAt: time.Date(2020, 12, 31, 12, 0, 0, 0, time.UTC), wantDay: "2020-12-31"},
	}
	for name, testCase := range cases {
		t.Run(name, func(t *testing.T) {
			repository := &fakeRecordPostViewRepository{}
			input := validRecordPostViewInput()
			input.ViewedAt = testCase.viewedAt

			if err := usecase.NewRecordPostView(repository, &fakeRecordPostViewCounter{}, 3, discardLogger()).Execute(context.Background(), input); err != nil {
				t.Fatalf("Execute() error = %v", err)
			}

			got := repository.inputs[0]
			if got.Day != testCase.wantDay || got.DailyLimit != 3 || !got.ViewedAt.Equal(testCase.viewedAt) || got.ViewedAt.Location() != time.UTC {
				t.Fatalf("Record() input = %+v, want day %s, limit 3 and the view time in UTC", got, testCase.wantDay)
			}
		})
	}
}

func TestRecordPostViewUsecase_Execute_RejectsADailyLimitBelowOneWithoutWriting(t *testing.T) {
	for _, limit := range []int{0, -1} {
		repository := &fakeRecordPostViewRepository{}
		counter := &fakeRecordPostViewCounter{}

		if err := usecase.NewRecordPostView(repository, counter, limit, discardLogger()).Execute(context.Background(), validRecordPostViewInput()); err == nil {
			t.Fatalf("Execute() with limit %d error = nil, want an error", limit)
		}
		if len(repository.inputs) != 0 || len(counter.calls) != 0 {
			t.Fatalf("limit %d: Record() calls = %d, AdjustViewCount() calls = %d, want 0", limit, len(repository.inputs), len(counter.calls))
		}
	}
}

func TestRecordPostViewUsecase_Execute_FailsOnAnUnknownDecisionWithoutCounting(t *testing.T) {
	counter := &fakeRecordPostViewCounter{}

	if err := newRecordPostView(&fakeRecordPostViewRepository{decision: 99}, counter, discardLogger()).Execute(context.Background(), validRecordPostViewInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error for an unknown decision")
	}
	if len(counter.calls) != 0 {
		t.Fatalf("AdjustViewCount() calls = %d, want 0", len(counter.calls))
	}
}
