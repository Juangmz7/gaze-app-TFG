package sweeper_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/sweeper"
)

type fakeSweep struct {
	runs chan struct{}
	err  error
}

func (f *fakeSweep) Execute(context.Context) (usecase.SweepStaleFeedSessionsOutput, error) {
	f.runs <- struct{}{}
	return usecase.SweepStaleFeedSessionsOutput{}, f.err
}

func TestPrefetchSweeper_Start_SweepsRepeatedlyUntilTheContextIsCancelled(t *testing.T) {
	tests := []struct {
		name string
		err  error
	}{
		{"sweeps that succeed", nil},
		{"a failing sweep does not stop the next", errors.New("mongo failed")},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sweep := &fakeSweep{runs: make(chan struct{}), err: tt.err}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := sweeper.NewPrefetchSweeper(sweep, time.Millisecond, slog.New(slog.NewTextHandler(io.Discard, nil))).Start(ctx)

			for range 2 {
				select {
				case <-sweep.runs:
				case <-time.After(5 * time.Second):
					t.Fatal("timed out waiting for a sweep")
				}
			}

			cancel()
			go func() {
				for range sweep.runs {
				}
			}()
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("the sweeper did not stop after the context was cancelled")
			}
			close(sweep.runs)
		})
	}
}
