package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeStaleSessions serves its sessions in order, by heartbeat, like the real query.
type fakeStaleSessions []usecase.FeedStaleSession

func (f fakeStaleSessions) FindStaleSessions(_ context.Context, _ time.Time, after *time.Time, limit int) ([]usecase.FeedStaleSession, error) {
	found := []usecase.FeedStaleSession{}
	for _, session := range f {
		if after != nil && !session.LastHeartbeatDate.After(*after) {
			continue
		}
		if len(found) == limit {
			break
		}
		found = append(found, session)
	}
	return found, nil
}

type fakeSweepPrefetch struct {
	inputs   []usecase.RequestFeedPrefetchInput
	statuses map[uuid.UUID]usecase.RequestFeedPrefetchStatus
	failFor  map[uuid.UUID]error
	onCall   func()
}

func (f *fakeSweepPrefetch) Execute(_ context.Context, input usecase.RequestFeedPrefetchInput) (usecase.RequestFeedPrefetchOutput, error) {
	f.inputs = append(f.inputs, input)
	if f.onCall != nil {
		f.onCall()
	}
	if err := f.failFor[input.UserID]; err != nil {
		return usecase.RequestFeedPrefetchOutput{}, err
	}
	status, found := f.statuses[input.UserID]
	if !found {
		status = usecase.RequestFeedPrefetchPublished
	}
	return usecase.RequestFeedPrefetchOutput{Status: status}, nil
}

func staleSessions(count int) fakeStaleSessions {
	base := time.Now().UTC().Add(-time.Hour)
	sessions := make(fakeStaleSessions, 0, count)
	for index := range count {
		sessions = append(sessions, usecase.FeedStaleSession{UserID: uuid.New(), LastHeartbeatDate: base.Add(time.Duration(index) * time.Second)})
	}
	return sessions
}

func TestSweepStaleFeedSessionsUsecase_Execute_WalksEveryBatchWithReasonStaleHeartbeat(t *testing.T) {
	sessions := staleSessions(5)
	prefetch := &fakeSweepPrefetch{}
	uc := usecase.NewSweepStaleFeedSessions(sessions, prefetch, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute, BatchSize: 2}, discardLogger())

	output, err := uc.Execute(context.Background())
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if output.Selected != 5 || output.Published != 5 || output.Failed != 0 {
		t.Fatalf("output = %+v", output)
	}
	for index, input := range prefetch.inputs {
		if input.UserID != sessions[index].UserID || input.Reason != usecase.FeedPrefetchReasonStaleHeartbeat || input.CorrelationID == "" {
			t.Fatalf("input %d = %+v", index, input)
		}
	}
}

// Without the cursor a user who stays due, because the lease is held, would be read
// again forever and the sweep would never end.
func TestSweepStaleFeedSessionsUsecase_Execute_EndsWhenUsersStayDueBecauseTheirLeaseIsHeld(t *testing.T) {
	sessions := staleSessions(4)
	statuses := map[uuid.UUID]usecase.RequestFeedPrefetchStatus{}
	for _, session := range sessions {
		statuses[session.UserID] = usecase.RequestFeedPrefetchDenied
	}
	prefetch := &fakeSweepPrefetch{statuses: statuses}
	uc := usecase.NewSweepStaleFeedSessions(sessions, prefetch, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute, BatchSize: 2}, discardLogger())

	output, err := uc.Execute(context.Background())
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(prefetch.inputs) != 4 || output.Published != 0 {
		t.Fatalf("calls = %d, output = %+v; want each user once and nothing published", len(prefetch.inputs), output)
	}
}

func TestSweepStaleFeedSessionsUsecase_Execute_ContinuesPastAUserWhosePrefetchFails(t *testing.T) {
	sessions := staleSessions(3)
	logger, logs := bufferLogger()
	prefetch := &fakeSweepPrefetch{failFor: map[uuid.UUID]error{sessions[1].UserID: errors.New("mongo failed")}}
	uc := usecase.NewSweepStaleFeedSessions(sessions, prefetch, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute, BatchSize: 10}, logger)

	output, err := uc.Execute(context.Background())
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if output.Selected != 3 || output.Published != 2 || output.Failed != 1 {
		t.Fatalf("output = %+v", output)
	}
	if !bytes.Contains(logs.Bytes(), []byte("userId="+sessions[1].UserID.String())) {
		t.Fatalf("logs = %s, want the failing user", logs)
	}
}

func TestSweepStaleFeedSessionsUsecase_Execute_StopsWhenTheContextIsCancelled(t *testing.T) {
	sessions := staleSessions(3)
	ctx, cancel := context.WithCancel(context.Background())
	prefetch := &fakeSweepPrefetch{onCall: cancel}
	uc := usecase.NewSweepStaleFeedSessions(sessions, prefetch, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute, BatchSize: 10}, discardLogger())

	if _, err := uc.Execute(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("Execute() error = %v, want context.Canceled", err)
	}
	if len(prefetch.inputs) != 1 {
		t.Fatalf("calls = %d, want it to stop after the cancellation", len(prefetch.inputs))
	}
}

func TestSweepStaleFeedSessionsUsecase_Execute_RejectsANonPositiveBatchSize(t *testing.T) {
	uc := usecase.NewSweepStaleFeedSessions(fakeStaleSessions{}, &fakeSweepPrefetch{}, usecase.SweepStaleFeedSessionsConfig{StaleAfter: time.Minute}, discardLogger())

	if _, err := uc.Execute(context.Background()); err == nil {
		t.Fatal("Execute() error = nil, want an error")
	}
}
