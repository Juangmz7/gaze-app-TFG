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

// fakeUserFeedWorld records the order of the steps of a response.
type fakeUserFeedWorld struct {
	steps     []string
	userFound bool
	gotUnits  usecase.BuildFeedUnitsInput
	inserted  int
	released  bool
	gotToken  uuid.UUID
	gotValue  any

	findErr, buildErr, markErr, releaseErr error
}

func (f *fakeUserFeedWorld) FindCreators(_ context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	f.steps = append(f.steps, "find user")
	if f.findErr != nil {
		return nil, f.findErr
	}
	if !f.userFound {
		return nil, nil
	}
	return []usecase.FeedCreator{{UserID: userIDs[0]}}, nil
}

func (f *fakeUserFeedWorld) Execute(ctx context.Context, input usecase.BuildFeedUnitsInput) (usecase.BuildFeedUnitsOutput, error) {
	f.steps = append(f.steps, "insert units")
	f.gotUnits = input
	f.gotValue = ctx.Value(contextKey{})
	return usecase.BuildFeedUnitsOutput{Inserted: f.inserted}, f.buildErr
}

func (f *fakeUserFeedWorld) MarkPrefetched(context.Context, uuid.UUID, time.Time) error {
	f.steps = append(f.steps, "mark prefetched")
	return f.markErr
}

func (f *fakeUserFeedWorld) Release(_ context.Context, _, token uuid.UUID, _ string) (bool, error) {
	f.steps = append(f.steps, "release lease")
	f.gotToken = token
	return f.released, f.releaseErr
}

func newTestBuildUserFeed(world *fakeUserFeedWorld) *usecase.BuildUserFeedUsecase {
	return usecase.NewBuildUserFeed(world, world, world, world, discardLogger())
}

func TestBuildUserFeedUsecase_Execute_InsertsMarksAndReleasesInThatOrder(t *testing.T) {
	world := &fakeUserFeedWorld{userFound: true, inserted: 2, released: true}
	token, userID := uuid.New(), uuid.New()
	posts := []uuid.UUID{uuid.New(), uuid.New()}
	ctx := context.WithValue(context.Background(), contextKey{}, "propagated")

	output, err := newTestBuildUserFeed(world).Execute(ctx, usecase.BuildUserFeedInput{UserID: userID, PostIDs: posts, LeaseToken: token})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if want := []string{"find user", "insert units", "mark prefetched", "release lease"}; !slices.Equal(world.steps, want) {
		t.Fatalf("steps = %v, want %v", world.steps, want)
	}
	if output.Inserted != 2 || !output.LeaseReleased || output.UserGone {
		t.Fatalf("output = %+v", output)
	}
	if world.gotToken != token || world.gotUnits.UserID != userID || !slices.Equal(world.gotUnits.PostIDs, posts) || world.gotUnits.CorrelationID != token.String() {
		t.Fatalf("token = %v, units = %+v", world.gotToken, world.gotUnits)
	}
	if world.gotValue != "propagated" {
		t.Fatal("the builder did not get the caller's context")
	}
}

func TestBuildUserFeedUsecase_Execute_AnEmptyListStillReleasesTheLease(t *testing.T) {
	world := &fakeUserFeedWorld{userFound: true, released: true}

	output, err := newTestBuildUserFeed(world).Execute(context.Background(), usecase.BuildUserFeedInput{UserID: uuid.New(), PostIDs: []uuid.UUID{}, LeaseToken: uuid.New()})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if !output.LeaseReleased {
		t.Fatalf("output = %+v, want the lease released", output)
	}
}

func TestBuildUserFeedUsecase_Execute_ALateResponseStoresItsUnitsWithoutReleasing(t *testing.T) {
	world := &fakeUserFeedWorld{userFound: true, inserted: 1, released: false}

	output, err := newTestBuildUserFeed(world).Execute(context.Background(), usecase.BuildUserFeedInput{UserID: uuid.New(), PostIDs: []uuid.UUID{uuid.New()}, LeaseToken: uuid.New()})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if output.Inserted != 1 || output.LeaseReleased {
		t.Fatalf("output = %+v, want the units stored and the lease kept", output)
	}
}

func TestBuildUserFeedUsecase_Execute_DropsAResponseForAUserWhoIsGone(t *testing.T) {
	// A user who is not projected yet does hold a lease: keeping it would leave them on
	// 503s until its TTL.
	world := &fakeUserFeedWorld{userFound: false, released: true}
	token := uuid.New()

	output, err := newTestBuildUserFeed(world).Execute(context.Background(), usecase.BuildUserFeedInput{UserID: uuid.New(), PostIDs: []uuid.UUID{uuid.New()}, LeaseToken: token})
	if err != nil {
		t.Fatalf("Execute() error = %v, want the response dropped, not retried", err)
	}

	if !output.UserGone || !output.LeaseReleased {
		t.Fatalf("output = %+v, want the user gone and the lease released", output)
	}
	if want := []string{"find user", "release lease"}; !slices.Equal(world.steps, want) {
		t.Fatalf("steps = %v, want %v: no write for a user who is gone, and the lease released", world.steps, want)
	}
	if world.gotToken != token {
		t.Fatalf("released token = %v, want the response's %v", world.gotToken, token)
	}
}

func TestBuildUserFeedUsecase_Execute_AFailureStopsTheLaterSteps(t *testing.T) {
	wantErr := errors.New("mongo failed")
	tests := []struct {
		name      string
		world     *fakeUserFeedWorld
		wantSteps []string
	}{
		{"reading the user", &fakeUserFeedWorld{findErr: wantErr}, []string{"find user"}},
		{"inserting units", &fakeUserFeedWorld{userFound: true, buildErr: wantErr}, []string{"find user", "insert units"}},
		{"marking", &fakeUserFeedWorld{userFound: true, markErr: wantErr}, []string{"find user", "insert units", "mark prefetched"}},
		{"releasing", &fakeUserFeedWorld{userFound: true, releaseErr: wantErr}, []string{"find user", "insert units", "mark prefetched", "release lease"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := newTestBuildUserFeed(tt.world).Execute(context.Background(), usecase.BuildUserFeedInput{UserID: uuid.New(), PostIDs: []uuid.UUID{uuid.New()}, LeaseToken: uuid.New()})

			if !errors.Is(err, wantErr) {
				t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
			}
			if !slices.Equal(tt.world.steps, tt.wantSteps) {
				t.Fatalf("steps = %v, want %v", tt.world.steps, tt.wantSteps)
			}
		})
	}
}

func TestBuildUserFeedUsecase_Execute_RejectsAMissingUserOrToken(t *testing.T) {
	tests := []struct {
		name  string
		input usecase.BuildUserFeedInput
	}{
		{"no user", usecase.BuildUserFeedInput{LeaseToken: uuid.New()}},
		{"no token", usecase.BuildUserFeedInput{UserID: uuid.New()}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			world := &fakeUserFeedWorld{userFound: true}
			if _, err := newTestBuildUserFeed(world).Execute(context.Background(), tt.input); err == nil {
				t.Fatal("Execute() error = nil, want an error")
			}
			if len(world.steps) != 0 {
				t.Fatalf("steps = %v, want none", world.steps)
			}
		})
	}
}
