package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// BuildUserFeedInput is one recommendation-service response. LeaseToken is its
// correlationId, the token the prefetch was requested under.
type BuildUserFeedInput struct {
	UserID     uuid.UUID
	PostIDs    []uuid.UUID
	LeaseToken uuid.UUID
}

// BuildUserFeedOutput says what the response led to. UserGone means it was dropped
// because the user is not in the read model.
type BuildUserFeedOutput struct {
	Inserted      int
	UserGone      bool
	LeaseReleased bool
}

// BuildUserFeedUserRepository checks the user is still there. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type BuildUserFeedUserRepository interface {
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
}

// BuildUserFeedUnitsBuilder stores the units. Implemented by BuildFeedUnitsUsecase.
type BuildUserFeedUnitsBuilder interface {
	Execute(ctx context.Context, input BuildFeedUnitsInput) (BuildFeedUnitsOutput, error)
}

// BuildUserFeedSessionRepository records the prefetch. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type BuildUserFeedSessionRepository interface {
	MarkPrefetched(ctx context.Context, userID uuid.UUID, now time.Time) error
}

// BuildUserFeedLease releases the lease by token. Implemented by FeedLeaseManager.
type BuildUserFeedLease interface {
	Release(ctx context.Context, userID, token uuid.UUID, correlationID string) (bool, error)
}

// BuildUserFeedUsecase applies a recommendation-service response to the user's feed.
type BuildUserFeedUsecase struct {
	users    BuildUserFeedUserRepository
	units    BuildUserFeedUnitsBuilder
	sessions BuildUserFeedSessionRepository
	lease    BuildUserFeedLease
	logger   *slog.Logger
}

// NewBuildUserFeed creates a BuildUserFeedUsecase.
func NewBuildUserFeed(users BuildUserFeedUserRepository, units BuildUserFeedUnitsBuilder, sessions BuildUserFeedSessionRepository, lease BuildUserFeedLease, logger *slog.Logger) *BuildUserFeedUsecase {
	return &BuildUserFeedUsecase{users: users, units: units, sessions: sessions, lease: lease, logger: logger}
}

// Execute inserts the units, records the prefetch and releases the lease, in that
// order and with no enclosing transaction: a crash at any step is safe, because the
// redelivery repeats them and the unique index on (user_id, post_id) deduplicates the
// units. An empty list still releases the lease. A late response stores its units but
// cannot release a newer lease, because its token no longer matches.
func (u *BuildUserFeedUsecase) Execute(ctx context.Context, input BuildUserFeedInput) (BuildUserFeedOutput, error) {
	if input.UserID == uuid.Nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: user id is required")
	}
	if input.LeaseToken == uuid.Nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: lease token is required")
	}
	correlationID := input.LeaseToken.String()

	users, err := u.users.FindCreators(ctx, []string{input.UserID.String()})
	if err != nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: %w", err)
	}
	if len(users) == 0 {
		u.logger.WarnContext(ctx, "dropping a feed response for a user who is not in the read model", "correlation_id", correlationID, "userId", input.UserID, "posts", len(input.PostIDs))
		released, err := u.lease.Release(ctx, input.UserID, input.LeaseToken, correlationID)
		if err != nil {
			return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: %w", err)
		}
		return BuildUserFeedOutput{UserGone: true, LeaseReleased: released}, nil
	}

	built, err := u.units.Execute(ctx, BuildFeedUnitsInput{UserID: input.UserID, PostIDs: input.PostIDs, CorrelationID: correlationID})
	if err != nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: %w", err)
	}

	if err := u.sessions.MarkPrefetched(ctx, input.UserID, time.Now().UTC()); err != nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: %w", err)
	}

	released, err := u.lease.Release(ctx, input.UserID, input.LeaseToken, correlationID)
	if err != nil {
		return BuildUserFeedOutput{}, fmt.Errorf("build user feed usecase: %w", err)
	}

	u.logger.InfoContext(ctx, "feed built from a recommendation response", "correlation_id", correlationID, "userId", input.UserID,
		"posts", len(input.PostIDs), "inserted", built.Inserted, "skipped", len(built.Skipped), "lease_released", released)

	return BuildUserFeedOutput{Inserted: built.Inserted, LeaseReleased: released}, nil
}
