package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

type fakeBuildRepositories struct {
	posts      map[string]usecase.FeedPost
	creators   map[string]usecase.FeedCreator
	nextFirst  int64
	reserved   []int64
	inserted   []usecase.FeedUnit
	insertErr  error
	reserveErr error
	gotCtx     []context.Context
}

func (f *fakeBuildRepositories) FindPosts(ctx context.Context, postIDs []string) ([]usecase.FeedPost, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	var found []usecase.FeedPost
	for _, postID := range postIDs {
		if post, ok := f.posts[postID]; ok {
			found = append(found, post)
		}
	}
	return found, nil
}

func (f *fakeBuildRepositories) FindCreators(ctx context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	var found []usecase.FeedCreator
	for _, userID := range userIDs {
		if creator, ok := f.creators[userID]; ok {
			found = append(found, creator)
		}
	}
	return found, nil
}

func (f *fakeBuildRepositories) ReservePositions(ctx context.Context, userID uuid.UUID, count int64) (int64, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	f.reserved = append(f.reserved, count)
	return f.nextFirst, f.reserveErr
}

func (f *fakeBuildRepositories) InsertUnits(ctx context.Context, units []usecase.FeedUnit) (int, error) {
	f.gotCtx = append(f.gotCtx, ctx)
	f.inserted = append(f.inserted, units...)
	return len(units), f.insertErr
}

func newBuildFixture(postIDs ...uuid.UUID) *fakeBuildRepositories {
	repositories := &fakeBuildRepositories{
		posts:     map[string]usecase.FeedPost{},
		creators:  map[string]usecase.FeedCreator{"creator-1": {UserID: "creator-1", Username: "ana"}},
		nextFirst: 40,
	}
	for _, postID := range postIDs {
		repositories.posts[postID.String()] = usecase.FeedPost{
			PostID:    postID.String(),
			CreatorID: "creator-1",
			PostType:  "BASIC",
			Tags:      []string{"travel"},
			Media:     []usecase.FeedMedia{{ID: "m1", URL: "a.jpg", MediaType: "IMAGE", Order: 1}},
		}
	}
	return repositories
}

func TestBuildFeedUnitsUsecase_Execute_StoresUnitsInTheRequestedOrderAtReservedPositions(t *testing.T) {
	first, second, third := uuid.New(), uuid.New(), uuid.New()
	repositories := newBuildFixture(first, second, third)
	uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())

	viewer := uuid.New()
	output, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{UserID: viewer, PostIDs: []uuid.UUID{third, first, second}})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if output.Inserted != 3 || len(output.Skipped) != 0 {
		t.Fatalf("output = %+v", output)
	}
	if len(repositories.reserved) != 1 || repositories.reserved[0] != 3 {
		t.Fatalf("reservations = %v, want one reservation for the whole batch", repositories.reserved)
	}
	for index, wantPost := range []uuid.UUID{third, first, second} {
		unit := repositories.inserted[index]
		if unit.PostID != wantPost.String() || unit.Position != 40+int64(index) || unit.UserID != viewer.String() {
			t.Fatalf("unit %d = %+v", index, unit)
		}
		if unit.Creator.Username != "ana" || unit.CoverThumbnailURL == nil || *unit.CoverThumbnailURL != "a.jpg" {
			t.Fatalf("unit %d snapshot = %+v", index, unit)
		}
		if unit.IngestedAt.IsZero() || unit.ServedAt != nil {
			t.Fatalf("unit %d dates: ingested %v, served %v", index, unit.IngestedAt, unit.ServedAt)
		}
	}
}

func TestBuildFeedUnitsUsecase_Execute_SkipsIDsNotYetProjectedWithAWarning(t *testing.T) {
	projected, missingPost, orphan := uuid.New(), uuid.New(), uuid.New()
	repositories := newBuildFixture(projected)
	repositories.posts[orphan.String()] = usecase.FeedPost{PostID: orphan.String(), CreatorID: "creator-not-projected"}
	logger, logs := bufferLogger()
	uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, logger)

	output, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{
		UserID:        uuid.New(),
		PostIDs:       []uuid.UUID{missingPost, projected, orphan},
		CorrelationID: "corr-1",
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repositories.inserted) != 1 || repositories.inserted[0].PostID != projected.String() {
		t.Fatalf("inserted = %+v, want only the projected post", repositories.inserted)
	}
	if len(output.Skipped) != 2 || output.Skipped[0] != missingPost || output.Skipped[1] != orphan {
		t.Fatalf("skipped = %v", output.Skipped)
	}
	if bytes.Count(logs.Bytes(), []byte("level=WARN")) != 2 || !bytes.Contains(logs.Bytes(), []byte("correlation_id=corr-1")) {
		t.Fatalf("want two warnings with the correlation id: %s", logs)
	}
}

func TestBuildFeedUnitsUsecase_Execute_ReservesNothingWhenNothingIsBuildable(t *testing.T) {
	repositories := newBuildFixture()
	uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())

	missing := uuid.New()
	output, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{UserID: uuid.New(), PostIDs: []uuid.UUID{missing}})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repositories.reserved) != 0 || len(repositories.inserted) != 0 {
		t.Fatalf("reserved = %v, inserted = %v, want neither", repositories.reserved, repositories.inserted)
	}
	if len(output.Skipped) != 1 || output.Skipped[0] != missing {
		t.Fatalf("skipped = %v", output.Skipped)
	}
}

func TestBuildFeedUnitsUsecase_Execute_DropsRepeatedAndNilIDs(t *testing.T) {
	postID := uuid.New()
	repositories := newBuildFixture(postID)
	uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())

	if _, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{UserID: uuid.New(), PostIDs: []uuid.UUID{postID, uuid.Nil, postID}}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repositories.reserved) != 1 || repositories.reserved[0] != 1 || len(repositories.inserted) != 1 {
		t.Fatalf("reserved = %v, inserted = %d, want one of each", repositories.reserved, len(repositories.inserted))
	}
}

func TestBuildFeedUnitsUsecase_Execute_ValidatesAndPropagatesErrors(t *testing.T) {
	wantErr := errors.New("mongo failed")
	postID := uuid.New()

	t.Run("missing user id", func(t *testing.T) {
		repositories := newBuildFixture(postID)
		uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())
		if _, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{PostIDs: []uuid.UUID{postID}}); err == nil {
			t.Fatal("Execute() error = nil, want an error")
		}
	})
	t.Run("reservation failure", func(t *testing.T) {
		repositories := newBuildFixture(postID)
		repositories.reserveErr = wantErr
		uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())
		if _, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{UserID: uuid.New(), PostIDs: []uuid.UUID{postID}}); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
	t.Run("insert failure", func(t *testing.T) {
		repositories := newBuildFixture(postID)
		repositories.insertErr = wantErr
		uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())
		if _, err := uc.Execute(context.Background(), usecase.BuildFeedUnitsInput{UserID: uuid.New(), PostIDs: []uuid.UUID{postID}}); !errors.Is(err, wantErr) {
			t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
		}
	})
}

func TestBuildFeedUnitsUsecase_Execute_PropagatesTheContextToEveryRepository(t *testing.T) {
	postID := uuid.New()
	repositories := newBuildFixture(postID)
	uc := usecase.NewBuildFeedUnits(repositories, repositories, repositories, discardLogger())

	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")
	if _, err := uc.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: uuid.New(), PostIDs: []uuid.UUID{postID}}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repositories.gotCtx) != 4 {
		t.Fatalf("repository calls = %d, want 4", len(repositories.gotCtx))
	}
	for index, got := range repositories.gotCtx {
		if got.Value(contextKey{}) != "propagated" {
			t.Fatalf("repository call %d did not receive the caller's context", index)
		}
	}
}
