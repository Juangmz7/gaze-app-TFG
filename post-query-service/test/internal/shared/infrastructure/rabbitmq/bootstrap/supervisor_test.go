package bootstrap_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/bootstrap"
)

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// fakeRunnerCloser is a bootstrap.RunnerCloser test double. Run and Close
// record every call (with timestamps) on a shared, mutex-guarded log so
// tests can assert ordering across an entire Supervise run without a real
// AMQP broker.
type fakeRunnerCloser struct {
	id int

	runFn   func(ctx context.Context) error
	closeFn func() error

	log *callLog
}

func (f *fakeRunnerCloser) Run(ctx context.Context) error {
	f.log.record("run_start", f.id)
	var err error
	if f.runFn != nil {
		err = f.runFn(ctx)
	}
	f.log.record("run_end", f.id)
	return err
}

func (f *fakeRunnerCloser) Close() error {
	f.log.record("close", f.id)
	if f.closeFn != nil {
		return f.closeFn()
	}
	return nil
}

// callLog records timestamped events from concurrent goroutines (the
// Supervise loop runs in its own goroutine) so tests can assert both
// ordering and elapsed time between events.
type callLog struct {
	mu     sync.Mutex
	events []event
}

type event struct {
	name string
	id   int
	at   time.Time
}

func (l *callLog) record(name string, id int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, event{name: name, id: id, at: time.Now()})
}

func (l *callLog) snapshot() []event {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]event, len(l.events))
	copy(out, l.events)
	return out
}

func waitForDone(t *testing.T, done <-chan struct{}, timeout time.Duration) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(timeout):
		t.Fatal("Supervise did not close its done channel in time")
	}
}

func TestSupervise_RetriesBootstrapFailuresWithIncreasingBackoffThenSucceeds(t *testing.T) {
	log := &callLog{}
	var mu sync.Mutex
	bootstrapCalls := 0

	fake := &fakeRunnerCloser{id: 1, log: log, runFn: func(ctx context.Context) error {
		<-ctx.Done()
		return nil
	}}

	bootstrapFn := func(ctx context.Context) (bootstrap.RunnerCloser, error) {
		mu.Lock()
		bootstrapCalls++
		n := bootstrapCalls
		mu.Unlock()
		log.record("bootstrap_attempt", n)
		if n <= 2 {
			return nil, errors.New("amqp dial failed")
		}
		return fake, nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := bootstrap.Supervise(ctx, bootstrapFn, testLogger())

	// Give the loop time to fail twice (2s, then 5s backoff) and succeed on
	// the third attempt, then reach Run (which blocks on ctx.Done()).
	deadline := time.After(10 * time.Second)
	for {
		mu.Lock()
		calls := bootstrapCalls
		mu.Unlock()
		if calls >= 3 {
			break
		}
		select {
		case <-deadline:
			t.Fatalf("bootstrap was not retried enough times, got %d calls", calls)
		case <-time.After(50 * time.Millisecond):
		}
	}

	events := log.snapshot()
	var attemptTimes []time.Time
	for _, e := range events {
		if e.name == "bootstrap_attempt" {
			attemptTimes = append(attemptTimes, e.at)
		}
	}
	if len(attemptTimes) < 3 {
		t.Fatalf("expected at least 3 bootstrap attempts, got %d", len(attemptTimes))
	}

	firstGap := attemptTimes[1].Sub(attemptTimes[0])
	secondGap := attemptTimes[2].Sub(attemptTimes[1])

	if firstGap < 1700*time.Millisecond {
		t.Fatalf("gap between attempt 1 and 2 = %v, want roughly 2s (supervisorBackoff(1))", firstGap)
	}
	if secondGap < 4700*time.Millisecond {
		t.Fatalf("gap between attempt 2 and 3 = %v, want roughly 5s (supervisorBackoff(2))", secondGap)
	}
	if secondGap <= firstGap {
		t.Fatalf("backoff did not increase: firstGap=%v secondGap=%v", firstGap, secondGap)
	}

	cancel()
	waitForDone(t, done, 5*time.Second)
}

func TestSupervise_ClosesBeforeNextBootstrapWhenRunReturnsAnError(t *testing.T) {
	log := &callLog{}
	var mu sync.Mutex
	bootstrapCalls := 0

	fakeFirst := &fakeRunnerCloser{id: 1, log: log, runFn: func(ctx context.Context) error {
		return errors.New("router stopped: connection lost")
	}}
	fakeSecond := &fakeRunnerCloser{id: 2, log: log, runFn: func(ctx context.Context) error {
		<-ctx.Done()
		return nil
	}}

	bootstrapFn := func(ctx context.Context) (bootstrap.RunnerCloser, error) {
		mu.Lock()
		bootstrapCalls++
		n := bootstrapCalls
		mu.Unlock()
		log.record("bootstrap_attempt", n)
		if n == 1 {
			return fakeFirst, nil
		}
		return fakeSecond, nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := bootstrap.Supervise(ctx, bootstrapFn, testLogger())

	deadline := time.After(10 * time.Second)
	for {
		mu.Lock()
		calls := bootstrapCalls
		mu.Unlock()
		if calls >= 2 {
			break
		}
		select {
		case <-deadline:
			t.Fatal("second bootstrap attempt never happened")
		case <-time.After(50 * time.Millisecond):
		}
	}

	cancel()
	waitForDone(t, done, 5*time.Second)

	events := log.snapshot()
	var closeIdx, secondBootstrapIdx = -1, -1
	for i, e := range events {
		if e.name == "close" && e.id == 1 && closeIdx == -1 {
			closeIdx = i
		}
		if e.name == "bootstrap_attempt" && e.id == 2 && secondBootstrapIdx == -1 {
			secondBootstrapIdx = i
		}
	}
	if closeIdx == -1 {
		t.Fatal("Close was never called on the first runnerCloser")
	}
	if secondBootstrapIdx == -1 {
		t.Fatal("second bootstrap call never happened")
	}
	if closeIdx > secondBootstrapIdx {
		t.Fatalf("Close on attempt 1 happened after the second bootstrap call (closeIdx=%d, secondBootstrapIdx=%d); resources must be closed before rebuilding", closeIdx, secondBootstrapIdx)
	}
}

func TestSupervise_CancelDuringBackoffSleepExitsPromptly(t *testing.T) {
	bootstrapFn := func(ctx context.Context) (bootstrap.RunnerCloser, error) {
		return nil, errors.New("amqp dial failed")
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := bootstrap.Supervise(ctx, bootstrapFn, testLogger())

	// supervisorBackoff(1) is 2s; cancel well before it elapses and verify
	// the loop does not wait it out.
	time.Sleep(200 * time.Millisecond)
	start := time.Now()
	cancel()

	waitForDone(t, done, 1*time.Second)

	if elapsed := time.Since(start); elapsed > 1*time.Second {
		t.Fatalf("Supervise took %v to exit after cancel, want well under the 2s backoff", elapsed)
	}
}

func TestSupervise_CancelWhileRunBlockedStillClosesAndExits(t *testing.T) {
	log := &callLog{}
	runStarted := make(chan struct{})

	fake := &fakeRunnerCloser{id: 1, log: log, runFn: func(ctx context.Context) error {
		close(runStarted)
		<-ctx.Done()
		return ctx.Err()
	}}

	bootstrapFn := func(ctx context.Context) (bootstrap.RunnerCloser, error) {
		return fake, nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := bootstrap.Supervise(ctx, bootstrapFn, testLogger())

	select {
	case <-runStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("Run was never started")
	}

	cancel()
	waitForDone(t, done, 2*time.Second)

	events := log.snapshot()
	closed := false
	for _, e := range events {
		if e.name == "close" && e.id == 1 {
			closed = true
		}
	}
	if !closed {
		t.Fatal("Close was not called after ctx cancellation unblocked Run")
	}
}

func TestSupervise_AttemptResetsToInitialBackoffAfterEachBootstrapSuccess(t *testing.T) {
	log := &callLog{}
	var mu sync.Mutex
	runStartCount := 0

	bootstrapFn := func(ctx context.Context) (bootstrap.RunnerCloser, error) {
		mu.Lock()
		n := runStartCount + 1
		mu.Unlock()
		return &fakeRunnerCloser{id: n, log: log, runFn: func(ctx context.Context) error {
			mu.Lock()
			runStartCount++
			mu.Unlock()
			// Run fails immediately every time; if attempt escalated across
			// cycles, the second post-failure backoff would be longer than
			// the first.
			return errors.New("connection reset")
		}}, nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := bootstrap.Supervise(ctx, bootstrapFn, testLogger())

	deadline := time.After(10 * time.Second)
	for {
		mu.Lock()
		n := runStartCount
		mu.Unlock()
		if n >= 3 {
			break
		}
		select {
		case <-deadline:
			t.Fatal("expected at least 3 run cycles")
		case <-time.After(50 * time.Millisecond):
		}
	}

	events := log.snapshot()
	var runStartTimes []time.Time
	for _, e := range events {
		if e.name == "run_start" {
			runStartTimes = append(runStartTimes, e.at)
		}
	}
	if len(runStartTimes) < 3 {
		t.Fatalf("expected at least 3 run_start events, got %d", len(runStartTimes))
	}

	firstGap := runStartTimes[1].Sub(runStartTimes[0])
	secondGap := runStartTimes[2].Sub(runStartTimes[1])
	// Every cycle is bootstrap-success-then-run-fails-immediately. If the
	// attempt counter resets to 0 after each successful bootstrap, every
	// inter-run gap should stay at the initial ~2s backoff
	// (supervisorBackoff(1)). If it does NOT reset, the second gap would
	// escalate to ~5s (supervisorBackoff(2)). The first gap alone cannot
	// distinguish the two cases (attempt starts at 0 either way), so the
	// critical assertion is on the second gap.
	if firstGap < 1700*time.Millisecond {
		t.Fatalf("gap between run cycles 1 and 2 = %v, want roughly the initial 2s backoff", firstGap)
	}
	if secondGap < 1700*time.Millisecond || secondGap > 3500*time.Millisecond {
		t.Fatalf("gap between run cycles 2 and 3 = %v, want roughly the initial 2s backoff (not escalated to ~5s)", secondGap)
	}

	cancel()
	waitForDone(t, done, 5*time.Second)
}
