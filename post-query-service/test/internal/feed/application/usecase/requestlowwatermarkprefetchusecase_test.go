package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

type fakeWatermarkPrefetch struct {
	started chan usecase.RequestFeedPrefetchInput
	release chan struct{}
	ctxErrs chan error
	err     error
}

func newFakeWatermarkPrefetch() *fakeWatermarkPrefetch {
	return &fakeWatermarkPrefetch{started: make(chan usecase.RequestFeedPrefetchInput, 8), release: make(chan struct{}), ctxErrs: make(chan error, 8)}
}

func (f *fakeWatermarkPrefetch) Execute(ctx context.Context, input usecase.RequestFeedPrefetchInput) (usecase.RequestFeedPrefetchOutput, error) {
	f.started <- input
	<-f.release
	f.ctxErrs <- ctx.Err()
	return usecase.RequestFeedPrefetchOutput{}, f.err
}

func newTestLowWatermark(prefetch *fakeWatermarkPrefetch, unserved, concurrency int) *usecase.RequestLowWatermarkPrefetchUsecase {
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{unserved: uuidStrings(unserved)}, usecase.FeedBacklogConfig{MaxUnserved: 60, ExcludedIDsCap: 100}, discardLogger())
	return usecase.NewRequestLowWatermarkPrefetch(backlog, prefetch, usecase.RequestLowWatermarkPrefetchConfig{PageSize: 15, Concurrency: concurrency, Timeout: time.Second}, discardLogger())
}

func TestRequestLowWatermarkPrefetchUsecase_Notify_RequestsAPrefetchBelowOnePageWithoutBlocking(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	uc := newTestLowWatermark(prefetch, 14, 2)
	userID := uuid.New()

	// It would hang here if the prefetch ran in the caller: the fake has not been released.
	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: userID, CorrelationID: "corr-1"})

	select {
	case input := <-prefetch.started:
		want := usecase.RequestFeedPrefetchInput{UserID: userID, Reason: usecase.FeedPrefetchReasonLowWatermark, CorrelationID: "corr-1"}
		if input != want {
			t.Fatalf("input = %+v, want %+v", input, want)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the prefetch was not requested")
	}
	close(prefetch.release)
	uc.Wait()
}

func TestRequestLowWatermarkPrefetchUsecase_Notify_RequestsNothingWithAtLeastOnePageUnserved(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	close(prefetch.release)
	uc := newTestLowWatermark(prefetch, 15, 2)

	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	uc.Wait()

	if len(prefetch.started) != 0 {
		t.Fatal("requested a prefetch with a full page unserved")
	}
}

func TestRequestLowWatermarkPrefetchUsecase_Notify_OutlivesTheClaimsRequestContext(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	uc := newTestLowWatermark(prefetch, 0, 2)

	ctx, cancel := context.WithCancel(context.Background())
	uc.Notify(ctx, usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	<-prefetch.started
	cancel()
	close(prefetch.release)
	uc.Wait()

	if err := <-prefetch.ctxErrs; err != nil {
		t.Fatalf("prefetch context error = %v, want a live context", err)
	}
}

func TestRequestLowWatermarkPrefetchUsecase_Notify_DropsACheckAboveTheConcurrencyBound(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	uc := newTestLowWatermark(prefetch, 0, 1)

	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	<-prefetch.started
	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	close(prefetch.release)
	uc.Wait()

	if len(prefetch.started) != 0 {
		t.Fatal("ran a second check above the bound")
	}
}

func TestRequestLowWatermarkPrefetchUsecase_Wait_StopsNewChecks(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	close(prefetch.release)
	uc := newTestLowWatermark(prefetch, 0, 1)
	uc.Wait()

	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	uc.Wait()

	if len(prefetch.started) != 0 {
		t.Fatal("ran a check after Wait")
	}
}

func TestRequestLowWatermarkPrefetchUsecase_Notify_SurvivesAFailedPrefetch(t *testing.T) {
	prefetch := newFakeWatermarkPrefetch()
	prefetch.err = errors.New("mongo failed")
	close(prefetch.release)
	uc := newTestLowWatermark(prefetch, 0, 1)

	uc.Notify(context.Background(), usecase.RequestLowWatermarkPrefetchInput{UserID: uuid.New()})
	uc.Wait()

	if len(prefetch.started) != 1 {
		t.Fatalf("prefetches = %d, want 1", len(prefetch.started))
	}
}
