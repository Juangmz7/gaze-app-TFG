package usecase

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// BuildFeedUnitsInput asks for stored feed units for UserID, in the order of PostIDs.
type BuildFeedUnitsInput struct {
	UserID        uuid.UUID
	PostIDs       []uuid.UUID
	CorrelationID string
}

// BuildFeedUnitsOutput says what happened to the requested ids. Inserted can be lower
// than the number of units built: a unit that already existed counts as applied.
type BuildFeedUnitsOutput struct {
	Inserted int
	Skipped  []uuid.UUID
}

// BuildFeedUnitsSourceRepository reads what a unit is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type BuildFeedUnitsSourceRepository interface {
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
}

// BuildFeedUnitsPositionRepository reserves positions. Implemented by
// feed/infrastructure/mongo.SessionRepository.
type BuildFeedUnitsPositionRepository interface {
	ReservePositions(ctx context.Context, userID uuid.UUID, count int64) (int64, error)
}

// BuildFeedUnitsWriteRepository stores units. Implemented by
// feed/infrastructure/mongo.FeedUnitRepository.
type BuildFeedUnitsWriteRepository interface {
	InsertUnits(ctx context.Context, units []FeedUnit) (int, error)
}

// BuildFeedUnitsUsecase turns an ordered list of post ids into stored feed units.
type BuildFeedUnitsUsecase struct {
	sources   BuildFeedUnitsSourceRepository
	positions BuildFeedUnitsPositionRepository
	units     BuildFeedUnitsWriteRepository
	logger    *slog.Logger
}

// NewBuildFeedUnits creates a BuildFeedUnitsUsecase.
func NewBuildFeedUnits(sources BuildFeedUnitsSourceRepository, positions BuildFeedUnitsPositionRepository, units BuildFeedUnitsWriteRepository, logger *slog.Logger) *BuildFeedUnitsUsecase {
	return &BuildFeedUnitsUsecase{sources: sources, positions: positions, units: units, logger: logger}
}

// Execute reads posts and creators with one $in query each, skips ids that are not
// projected yet with a warning, reserves one position per remaining unit and stores
// them. Overlapping or retried builds only burn positions, and gaps are allowed:
// the unique indexes on feed_units make the second insert of a unit a no-op.
func (u *BuildFeedUnitsUsecase) Execute(ctx context.Context, input BuildFeedUnitsInput) (BuildFeedUnitsOutput, error) {
	if input.UserID == uuid.Nil {
		return BuildFeedUnitsOutput{}, fmt.Errorf("build feed units usecase: user id is required")
	}

	postIDs := u.uniquePostIDs(ctx, input)
	if len(postIDs) == 0 {
		return BuildFeedUnitsOutput{}, nil
	}

	posts, creators, err := u.readSources(ctx, postIDs)
	if err != nil {
		return BuildFeedUnitsOutput{}, err
	}

	buildable, skipped := u.selectBuildable(ctx, input, postIDs, posts, creators)
	if len(buildable) == 0 {
		return BuildFeedUnitsOutput{Skipped: skipped}, nil
	}

	first, err := u.positions.ReservePositions(ctx, input.UserID, int64(len(buildable)))
	if err != nil {
		return BuildFeedUnitsOutput{}, fmt.Errorf("build feed units usecase: %w", err)
	}

	now := time.Now().UTC()
	units := make([]FeedUnit, 0, len(buildable))
	for index, post := range buildable {
		units = append(units, FeedUnitFromPost(input.UserID.String(), first+int64(index), post, creators[post.CreatorID], now))
	}

	inserted, err := u.units.InsertUnits(ctx, units)
	if err != nil {
		return BuildFeedUnitsOutput{}, fmt.Errorf("build feed units usecase: %w", err)
	}

	u.logger.InfoContext(ctx, "feed units built", "correlation_id", input.CorrelationID, "userId", input.UserID,
		"requested", len(postIDs), "inserted", inserted, "skipped", len(skipped))

	return BuildFeedUnitsOutput{Inserted: inserted, Skipped: skipped}, nil
}

// uniquePostIDs drops nil and repeated ids, keeping the first occurrence's order.
func (u *BuildFeedUnitsUsecase) uniquePostIDs(ctx context.Context, input BuildFeedUnitsInput) []uuid.UUID {
	seen := make(map[uuid.UUID]struct{}, len(input.PostIDs))
	postIDs := make([]uuid.UUID, 0, len(input.PostIDs))
	for _, postID := range input.PostIDs {
		if postID == uuid.Nil {
			u.logger.WarnContext(ctx, "discarding a nil post id while building feed units", "correlation_id", input.CorrelationID, "userId", input.UserID)
			continue
		}
		if _, repeated := seen[postID]; repeated {
			continue
		}
		seen[postID] = struct{}{}
		postIDs = append(postIDs, postID)
	}

	return postIDs
}

func (u *BuildFeedUnitsUsecase) readSources(ctx context.Context, postIDs []uuid.UUID) (map[string]FeedPost, map[string]FeedCreator, error) {
	ids := make([]string, 0, len(postIDs))
	for _, postID := range postIDs {
		ids = append(ids, postID.String())
	}

	foundPosts, err := u.sources.FindPosts(ctx, ids)
	if err != nil {
		return nil, nil, fmt.Errorf("build feed units usecase: %w", err)
	}
	posts := make(map[string]FeedPost, len(foundPosts))
	creatorIDs := make([]string, 0, len(foundPosts))
	for _, post := range foundPosts {
		posts[post.PostID] = post
		creatorIDs = append(creatorIDs, post.CreatorID)
	}
	if len(creatorIDs) == 0 {
		return posts, map[string]FeedCreator{}, nil
	}

	foundCreators, err := u.sources.FindCreators(ctx, creatorIDs)
	if err != nil {
		return nil, nil, fmt.Errorf("build feed units usecase: %w", err)
	}
	creators := make(map[string]FeedCreator, len(foundCreators))
	for _, creator := range foundCreators {
		creators[creator.UserID] = creator
	}

	return posts, creators, nil
}

// selectBuildable keeps the requested order and sets aside ids whose post or creator
// is not projected yet.
func (u *BuildFeedUnitsUsecase) selectBuildable(ctx context.Context, input BuildFeedUnitsInput, postIDs []uuid.UUID, posts map[string]FeedPost, creators map[string]FeedCreator) ([]FeedPost, []uuid.UUID) {
	buildable := make([]FeedPost, 0, len(postIDs))
	var skipped []uuid.UUID
	for _, postID := range postIDs {
		post, found := posts[postID.String()]
		if !found {
			skipped = append(skipped, postID)
			u.logger.WarnContext(ctx, "skipping a post not projected yet while building feed units", "correlation_id", input.CorrelationID, "userId", input.UserID, "postId", postID)
			continue
		}
		if _, found := creators[post.CreatorID]; !found {
			skipped = append(skipped, postID)
			u.logger.WarnContext(ctx, "skipping a post whose creator is not projected yet while building feed units", "correlation_id", input.CorrelationID, "userId", input.UserID, "postId", postID, "creatorId", post.CreatorID)
			continue
		}
		buildable = append(buildable, post)
	}

	return buildable, skipped
}
