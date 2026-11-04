package mongo

import (
	"context"
	"fmt"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// Read models the feed reads but does not own. Their contexts write them, and create
// the indexes these queries need in their own EnsureIndexes.
const (
	postsCollectionName   = "posts"
	usersCollectionName   = "users"
	collabsCollectionName = "post_collabs"
	likesCollectionName   = "post_likes"
	sharesCollectionName  = "post_shares"
	followsCollectionName = "user_follows"
	blocksCollectionName  = "user_blocks"
)

// HydrationRepository reads the current read models for a batch of feed units: one
// projected $in query per method, so a page never costs one query per item.
type HydrationRepository struct {
	db *mongo.Database
}

// NewHydrationRepository creates a HydrationRepository backed by db.
func NewHydrationRepository(db *mongo.Database) *HydrationRepository {
	return &HydrationRepository{db: db}
}

// FindPosts reads the named posts. Posts not projected are absent from the result.
func (r *HydrationRepository) FindPosts(ctx context.Context, postIDs []string) ([]usecase.FeedPost, error) {
	if len(postIDs) == 0 {
		return nil, nil
	}

	var documents []usecase.FeedPostDocument
	err := r.find(ctx, postsCollectionName, bson.D{{Key: "post_id", Value: in(postIDs)}}, bson.D{
		{Key: "post_id", Value: 1},
		{Key: "user_id", Value: 1},
		{Key: "collab_id", Value: 1},
		{Key: "post_type", Value: 1},
		{Key: "description", Value: 1},
		{Key: "tags", Value: 1},
		{Key: "media", Value: 1},
		{Key: "stats", Value: 1},
		{Key: "created_at", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	posts := make([]usecase.FeedPost, 0, len(documents))
	for _, document := range documents {
		posts = append(posts, usecase.FeedPostFromDocument(document))
	}

	return posts, nil
}

// FindCreators reads the named users. Users not projected are absent from the result.
func (r *HydrationRepository) FindCreators(ctx context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	if len(userIDs) == 0 {
		return nil, nil
	}

	var documents []usecase.FeedUserDocument
	err := r.find(ctx, usersCollectionName, bson.D{{Key: "user_id", Value: in(userIDs)}}, bson.D{
		{Key: "user_id", Value: 1},
		{Key: "username", Value: 1},
		{Key: "picture_url", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	creators := make([]usecase.FeedCreator, 0, len(documents))
	for _, document := range documents {
		creators = append(creators, usecase.FeedCreatorFromDocument(document))
	}

	return creators, nil
}

// FindCollabs reads the collabs with one of collabIDs or attached to one of postIDs,
// because a post can reach a collab both ways: a collab opened on an existing post
// never sets the post's collab_id, and a linked post's collab names another post.
func (r *HydrationRepository) FindCollabs(ctx context.Context, collabIDs, postIDs []string) ([]usecase.FeedCollab, error) {
	if len(collabIDs) == 0 && len(postIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "collab_id", Value: in(collabIDs)}},
		bson.D{{Key: "post_id", Value: in(postIDs)}},
	}}}

	var documents []usecase.FeedCollabDocument
	err := r.find(ctx, collabsCollectionName, filter, bson.D{
		{Key: "collab_id", Value: 1},
		{Key: "post_id", Value: 1},
		{Key: "title", Value: 1},
		{Key: "status", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	collabs := make([]usecase.FeedCollab, 0, len(documents))
	for _, document := range documents {
		collabs = append(collabs, usecase.FeedCollabFromDocument(document))
	}

	return collabs, nil
}

// FindLikedPostIDs returns which of postIDs the viewer liked.
func (r *HydrationRepository) FindLikedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error) {
	return r.findViewerPostIDs(ctx, likesCollectionName, viewerID, postIDs)
}

// FindSharedPostIDs returns which of postIDs the viewer shared.
func (r *HydrationRepository) FindSharedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error) {
	return r.findViewerPostIDs(ctx, sharesCollectionName, viewerID, postIDs)
}

// FindFollowedUserIDs returns which of userIDs the viewer follows.
func (r *HydrationRepository) FindFollowedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error) {
	if len(userIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{
		{Key: "follower_user_id", Value: viewerID},
		{Key: "followed_user_id", Value: in(userIDs)},
	}

	var documents []struct {
		FollowedUserID string `bson:"followed_user_id"`
	}
	if err := r.find(ctx, followsCollectionName, filter, bson.D{{Key: "followed_user_id", Value: 1}}, &documents); err != nil {
		return nil, err
	}

	followed := make([]string, 0, len(documents))
	for _, document := range documents {
		followed = append(followed, document.FollowedUserID)
	}

	return followed, nil
}

// FindBlockRelatedUserIDs returns which of userIDs have a block with the viewer, in
// either direction.
func (r *HydrationRepository) FindBlockRelatedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error) {
	if len(userIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "blocker_user_id", Value: viewerID}, {Key: "blocked_user_id", Value: in(userIDs)}},
		bson.D{{Key: "blocked_user_id", Value: viewerID}, {Key: "blocker_user_id", Value: in(userIDs)}},
	}}}

	var documents []struct {
		BlockerUserID string `bson:"blocker_user_id"`
		BlockedUserID string `bson:"blocked_user_id"`
	}
	err := r.find(ctx, blocksCollectionName, filter, bson.D{
		{Key: "blocker_user_id", Value: 1},
		{Key: "blocked_user_id", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	related := make([]string, 0, len(documents))
	for _, document := range documents {
		if document.BlockerUserID == viewerID {
			related = append(related, document.BlockedUserID)
			continue
		}
		related = append(related, document.BlockerUserID)
	}

	return related, nil
}

func (r *HydrationRepository) findViewerPostIDs(ctx context.Context, collection, viewerID string, postIDs []string) ([]string, error) {
	if len(postIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{{Key: "user_id", Value: viewerID}, {Key: "post_id", Value: in(postIDs)}}

	var documents []struct {
		PostID string `bson:"post_id"`
	}
	if err := r.find(ctx, collection, filter, bson.D{{Key: "post_id", Value: 1}}, &documents); err != nil {
		return nil, err
	}

	found := make([]string, 0, len(documents))
	for _, document := range documents {
		found = append(found, document.PostID)
	}

	return found, nil
}

// find runs one projected query and decodes every result into out.
func (r *HydrationRepository) find(ctx context.Context, collection string, filter, projection bson.D, out any) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	cursor, err := r.db.Collection(collection).Find(ctx, filter, options.Find().SetProjection(projection))
	if err != nil {
		return fmt.Errorf("find in %s: %w", collection, err)
	}
	if err := cursor.All(ctx, out); err != nil {
		return fmt.Errorf("read from %s: %w", collection, err)
	}

	return nil
}

// in builds a $in with a list that is never null, which the server would reject.
func in(values []string) bson.D {
	if values == nil {
		values = []string{}
	}
	return bson.D{{Key: "$in", Value: values}}
}
