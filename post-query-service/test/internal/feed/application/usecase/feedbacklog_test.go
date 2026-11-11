package usecase_test

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeBacklogRepository holds a feed as two lists of post ids.
type fakeBacklogRepository struct {
	unserved    []string
	served      []string
	countLimits []int64
	err         error
	gotCtx      []context.Context
}

func (f *fakeBacklogRepository) CountUnserved(ctx context.Context, _ string, limit int64) (int64, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	f.countLimits = append(f.countLimits, limit)
	return min(int64(len(f.unserved)), limit), f.err
}

func (f *fakeBacklogRepository) FindAfterPosition(ctx context.Context, _ string, _ *int64, unservedOnly bool, limit int) ([]usecase.FeedUnitRef, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	if f.err != nil {
		return nil, f.err
	}
	if !unservedOnly {
		return nil, errors.New("the backlog must only read unserved units")
	}
	refs := []usecase.FeedUnitRef{}
	for index, postID := range f.unserved {
		if len(refs) == limit {
			break
		}
		refs = append(refs, usecase.FeedUnitRef{PostID: postID, Position: int64(index), PostCreatedAt: time.Time{}})
	}
	return refs, nil
}

func (f *fakeBacklogRepository) FindRecentlyServedPostIDs(_ context.Context, _ string, limit int) ([]string, error) {
	return f.served[:min(limit, len(f.served))], f.err
}

func uuidStrings(count int) []string {
	ids := make([]string, 0, count)
	for range count {
		ids = append(ids, uuid.NewString())
	}
	return ids
}

func parseUUIDs(t *testing.T, ids []string) []uuid.UUID {
	t.Helper()
	parsed := make([]uuid.UUID, 0, len(ids))
	for _, id := range ids {
		parsed = append(parsed, uuid.MustParse(id))
	}
	return parsed
}

func TestFeedBacklog_ExcludedPostIDs_SendsTheUnservedThenTheMostRecentlyServed(t *testing.T) {
	unserved, served := uuidStrings(2), uuidStrings(2)
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{unserved: unserved, served: served}, usecase.FeedBacklogConfig{MaxUnserved: 10, ExcludedIDsCap: 10}, discardLogger())

	got, err := backlog.ExcludedPostIDs(context.Background(), uuid.New())
	if err != nil {
		t.Fatalf("ExcludedPostIDs() error = %v", err)
	}

	want := parseUUIDs(t, append(slices.Clone(unserved), served...))
	if !slices.Equal(got, want) {
		t.Fatalf("ExcludedPostIDs() = %v, want %v", got, want)
	}
}

func TestFeedBacklog_ExcludedPostIDs_CapsTheListAndPrefersTheUnserved(t *testing.T) {
	unserved, served := uuidStrings(3), uuidStrings(5)
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{unserved: unserved, served: served}, usecase.FeedBacklogConfig{MaxUnserved: 10, ExcludedIDsCap: 4}, discardLogger())

	got, err := backlog.ExcludedPostIDs(context.Background(), uuid.New())
	if err != nil {
		t.Fatalf("ExcludedPostIDs() error = %v", err)
	}

	want := parseUUIDs(t, append(slices.Clone(unserved), served[0]))
	if !slices.Equal(got, want) {
		t.Fatalf("ExcludedPostIDs() = %v, want %v", got, want)
	}
}

func TestFeedBacklog_ExcludedPostIDs_ReturnsAnEmptyListForAnEmptyFeed(t *testing.T) {
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{}, usecase.FeedBacklogConfig{MaxUnserved: 10, ExcludedIDsCap: 4}, discardLogger())

	got, err := backlog.ExcludedPostIDs(context.Background(), uuid.New())
	if err != nil {
		t.Fatalf("ExcludedPostIDs() error = %v", err)
	}
	if got == nil || len(got) != 0 {
		t.Fatalf("ExcludedPostIDs() = %#v, want an empty, non-nil list", got)
	}
}

func TestFeedBacklog_ExcludedPostIDs_DiscardsAStoredIDThatIsNotAUUID(t *testing.T) {
	valid := uuid.NewString()
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{unserved: []string{"not-a-uuid", valid}}, usecase.FeedBacklogConfig{MaxUnserved: 10, ExcludedIDsCap: 4}, discardLogger())

	got, err := backlog.ExcludedPostIDs(context.Background(), uuid.New())
	if err != nil {
		t.Fatalf("ExcludedPostIDs() error = %v", err)
	}
	if len(got) != 1 || got[0].String() != valid {
		t.Fatalf("ExcludedPostIDs() = %v, want only %s", got, valid)
	}
}

func TestFeedBacklog_ExcludedPostIDs_ReturnsARepositoryFailure(t *testing.T) {
	wantErr := errors.New("mongo failed")
	backlog := usecase.NewFeedBacklog(&fakeBacklogRepository{err: wantErr}, usecase.FeedBacklogConfig{MaxUnserved: 10, ExcludedIDsCap: 4}, discardLogger())

	if _, err := backlog.ExcludedPostIDs(context.Background(), uuid.New()); !errors.Is(err, wantErr) {
		t.Fatalf("ExcludedPostIDs() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestFeedBacklog_Unserved_ReportsWhetherTheMaximumIsReached(t *testing.T) {
	tests := []struct {
		name     string
		stored   int
		wantFull bool
		want     int64
	}{
		{name: "below the maximum", stored: 2, wantFull: false, want: 2},
		{name: "at the maximum", stored: 3, wantFull: true, want: 3},
		{name: "above the maximum", stored: 8, wantFull: true, want: 3},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeBacklogRepository{unserved: uuidStrings(tt.stored)}
			backlog := usecase.NewFeedBacklog(repository, usecase.FeedBacklogConfig{MaxUnserved: 3, ExcludedIDsCap: 3}, discardLogger())

			count, full, err := backlog.Unserved(context.Background(), uuid.New())
			if err != nil {
				t.Fatalf("Unserved() error = %v", err)
			}
			if count != tt.want || full != tt.wantFull {
				t.Fatalf("Unserved() = %d, %v; want %d, %v", count, full, tt.want, tt.wantFull)
			}
			if repository.countLimits[0] != 3 {
				t.Fatalf("count limit = %d, want the maximum, so the count stays bounded", repository.countLimits[0])
			}
		})
	}
}
