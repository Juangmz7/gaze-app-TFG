package mongo_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
)

// postDocument mirrors the persisted shape written by postmongo.Repository,
// used here only to decode what was actually written to MongoDB.
type postDocument struct {
	PostID      string    `bson:"post_id"`
	UserID      string    `bson:"user_id"`
	CollabID    string    `bson:"collab_id"`
	PostType    string    `bson:"post_type"`
	Description string    `bson:"description"`
	Tags        []string  `bson:"tags"`
	UpdatedAt   time.Time `bson:"updated_at"`
	Version     int64     `bson:"version"`
}

// TestRepository_Insert_PersistsThePostReadModelAtVersionOne proves the
// repository actually writes a retrievable document to MongoDB with the
// expected fields and starts it at version 1.
func TestRepository_Insert_PersistsThePostReadModelAtVersionOne(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	input := usecase.CreatePostInput{
		PostID:      uuid.New(),
		UserID:      uuid.New(),
		PostType:    "TEXT",
		Description: "a distributed systems post",
		Tags:        []string{"go", "rabbitmq"},
		CreatedAt:   time.Now().UTC(),
		UpdatedAt:   time.Now().UTC(),
	}

	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findPost(t, ctx, db, input.PostID)
	if got.UserID != input.UserID.String() {
		t.Fatalf("stored document UserID = %q, want %q", got.UserID, input.UserID.String())
	}
	if got.PostType != input.PostType {
		t.Fatalf("stored document PostType = %q, want %q", got.PostType, input.PostType)
	}
	if got.Description != input.Description {
		t.Fatalf("stored document Description = %q, want %q", got.Description, input.Description)
	}
	if len(got.Tags) != 2 || got.Tags[0] != "go" || got.Tags[1] != "rabbitmq" {
		t.Fatalf("stored document Tags = %v, want [go rabbitmq]", got.Tags)
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want 1", got.Version)
	}
}

// TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSamePostID proves
// the crash-and-retry race named by Insert's doc comment: a unique index on
// post_id makes a second Insert of the same business id a no-op (duplicate
// key treated as already-applied) rather than a second document or an
// error, so EnsureIndexes must run first.
func TestRepository_Insert_IsIdempotentOnARetriedInsertOfTheSamePostID(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)
	if err := repository.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	postID := uuid.New()
	input := usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Insert(ctx, input); err != nil {
		t.Fatalf("Insert() error = %v, want nil on a retried insert of the same post id", err)
	}

	count, err := db.Collection("posts").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 1 {
		t.Fatalf("CountDocuments() = %d, want exactly 1 document for post_id %s", count, postID)
	}
}

// TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches
// proves Update applies the optimistic-concurrency contract: it only
// succeeds when the stored version matches, and bumps the version on
// success.
func TestRepository_Update_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdatePostInput{
		PostID:          postID,
		ExpectedVersion: 1,
		PostType:        "TEXT",
		Description:     "updated version",
		Tags:            []string{"edited"},
		UpdatedAt:       time.Now().UTC(),
	}
	if err := repository.Update(ctx, update); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.Description != "updated version" {
		t.Fatalf("stored document Description = %q, want %q", got.Description, "updated version")
	}
	if len(got.Tags) != 1 || got.Tags[0] != "edited" {
		t.Fatalf("stored document Tags = %v, want [edited]", got.Tags)
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

// TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale
// proves a stale ExpectedVersion neither modifies the document nor silently
// succeeds; it returns ErrVersionConflict.
func TestRepository_Update_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	update := usecase.UpdatePostInput{PostID: postID, ExpectedVersion: 99, PostType: "TEXT", Description: "should not apply"}
	err := repository.Update(ctx, update)
	if !errors.Is(err, postmongo.ErrVersionConflict) {
		t.Fatalf("Update() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.Description != "first version" {
		t.Fatalf("stored document Description = %q, want unchanged %q", got.Description, "first version")
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

// TestRepository_LinkCollab_SucceedsAndIncrementsVersionWhenExpectedVersionMatches
// proves LinkCollab applies the optimistic-concurrency contract and only
// writes collab_id, leaving post content fields untouched.
func TestRepository_LinkCollab_SucceedsAndIncrementsVersionWhenExpectedVersionMatches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	collabID := uuid.New()
	link := usecase.LinkPostCollabInput{
		PostID:          postID,
		CollabID:        collabID,
		ExpectedVersion: 1,
	}
	if err := repository.LinkCollab(ctx, link); err != nil {
		t.Fatalf("LinkCollab() error = %v", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.CollabID != collabID.String() {
		t.Fatalf("stored document CollabID = %q, want %q", got.CollabID, collabID.String())
	}
	if got.Description != "first version" {
		t.Fatalf("stored document Description = %q, want unchanged %q", got.Description, "first version")
	}
	if got.Version != 2 {
		t.Fatalf("stored document Version = %d, want 2", got.Version)
	}
}

// TestRepository_LinkCollab_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale
// proves a stale ExpectedVersion neither modifies the document nor silently
// succeeds; it returns ErrVersionConflict.
func TestRepository_LinkCollab_ReturnsVersionConflictAndDoesNotModifyWhenExpectedVersionIsStale(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	link := usecase.LinkPostCollabInput{PostID: postID, CollabID: uuid.New(), ExpectedVersion: 99}
	err := repository.LinkCollab(ctx, link)
	if !errors.Is(err, postmongo.ErrVersionConflict) {
		t.Fatalf("LinkCollab() error = %v, want it to wrap ErrVersionConflict", err)
	}

	got := findPost(t, ctx, db, postID)
	if got.CollabID != "" {
		t.Fatalf("stored document CollabID = %q, want unchanged empty string", got.CollabID)
	}
	if got.Version != 1 {
		t.Fatalf("stored document Version = %d, want unchanged 1", got.Version)
	}
}

func TestRepository_GetVersion_ReturnsTheStoredVersionWhenThePostExists(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	version, found, err := repository.GetVersion(ctx, postID)
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if !found {
		t.Fatal("GetVersion() found = false, want true for an existing post")
	}
	if version != 1 {
		t.Fatalf("GetVersion() version = %d, want 1", version)
	}
}

func TestRepository_GetVersion_ReturnsNotFoundWhenThePostWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	_, found, err := repository.GetVersion(ctx, uuid.New())
	if err != nil {
		t.Fatalf("GetVersion() error = %v", err)
	}
	if found {
		t.Fatal("GetVersion() found = true, want false for a post that was never projected")
	}
}

func TestRepository_Delete_RemovesAnExistingPostReadModelDocument(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "TEXT", Description: "first version"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	if err := repository.Delete(ctx, postID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}

	count, err := db.Collection("posts").CountDocuments(ctx, bson.D{{Key: "post_id", Value: postID.String()}})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if count != 0 {
		t.Fatalf("CountDocuments() = %d, want 0 after Delete", count)
	}
}

func TestRepository_Delete_IsANoOpWhenThePostWasNeverProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	if err := repository.Delete(ctx, uuid.New()); err != nil {
		t.Fatalf("Delete() error = %v, want nil for an absent document", err)
	}
}

func findPost(t *testing.T, ctx context.Context, db *mongodriver.Database, postID uuid.UUID) postDocument {
	t.Helper()

	var doc postDocument
	err := db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}).Decode(&doc)
	if err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return doc
}

func testDatabase(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	container, err := mongodb.Run(ctx, "mongo:7")
	if err != nil {
		t.Fatalf("mongodb.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})

	connectionString, err := container.ConnectionString(ctx)
	if err != nil {
		t.Fatalf("container.ConnectionString() error = %v", err)
	}

	client, err := mongodriver.Connect(options.Client().ApplyURI(connectionString))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := client.Disconnect(context.Background()); disconnectErr != nil {
			t.Logf("client.Disconnect() error = %v", disconnectErr)
		}
	})

	return client.Database("post_query_post_repository_test")
}

// storedPost decodes the media and stats fields exactly as they are named in
// MongoDB, which other readers of the posts collection depend on.
type storedPost struct {
	Media []struct {
		ID           string   `bson:"id"`
		URL          string   `bson:"url"`
		ThumbnailURL *string  `bson:"thumbnail_url"`
		MediaType    string   `bson:"media_type"`
		DurationMS   *int64   `bson:"duration_ms"`
		TaggedUsers  []string `bson:"tagged_users"`
		Order        int      `bson:"order"`
	} `bson:"media"`
	Stats struct {
		LikeCount    int64 `bson:"like_count"`
		CommentCount int64 `bson:"comment_count"`
		ShareCount   int64 `bson:"share_count"`
	} `bson:"stats"`
	Tags    []string `bson:"tags"`
	Version int64    `bson:"version"`
}

func findStoredPost(t *testing.T, ctx context.Context, db *mongodriver.Database, postID uuid.UUID) storedPost {
	t.Helper()

	var doc storedPost
	if err := db.Collection("posts").FindOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}}).Decode(&doc); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}

	return doc
}

func samplePostMedia() []usecase.PostMedia {
	thumbnail := "https://media.example.test/harbour-thumb.jpg"
	duration := int64(15000)

	return []usecase.PostMedia{
		{ID: uuid.New(), URL: "https://media.example.test/sunset.jpg", MediaType: usecase.PostMediaTypeImage, TaggedUsers: []string{"ana_rivera"}, Order: 1},
		{ID: uuid.New(), URL: "https://media.example.test/harbour.mp4", ThumbnailURL: &thumbnail, MediaType: usecase.PostMediaTypeVideo, Duration: &duration, Order: 2},
	}
}

func TestRepository_Insert_StoresEveryMediaFieldTogetherAndStartsStatsAtZero(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)
	media := samplePostMedia()

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC", Tags: []string{"travel"}, Media: media}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	got := findStoredPost(t, ctx, db, postID)
	if len(got.Media) != 2 {
		t.Fatalf("stored media length = %d, want 2", len(got.Media))
	}
	image, video := got.Media[0], got.Media[1]
	if image.ID != media[0].ID.String() || image.URL != media[0].URL || image.MediaType != "IMAGE" || image.Order != 1 {
		t.Fatalf("stored media[0] = %+v", image)
	}
	if image.ThumbnailURL != nil || image.DurationMS != nil {
		t.Fatalf("stored media[0] thumbnail/duration = %v/%v, want null", image.ThumbnailURL, image.DurationMS)
	}
	if len(image.TaggedUsers) != 1 || image.TaggedUsers[0] != "ana_rivera" {
		t.Fatalf("stored media[0] tagged users = %v", image.TaggedUsers)
	}
	if video.ThumbnailURL == nil || *video.ThumbnailURL != *media[1].ThumbnailURL {
		t.Fatalf("stored media[1] thumbnail = %v", video.ThumbnailURL)
	}
	if video.DurationMS == nil || *video.DurationMS != 15000 {
		t.Fatalf("stored media[1] duration = %v, want 15000 unchanged", video.DurationMS)
	}
	if video.TaggedUsers == nil {
		t.Fatal("stored media[1] tagged users = null, want an empty list")
	}
	if got.Stats.LikeCount != 0 || got.Stats.CommentCount != 0 || got.Stats.ShareCount != 0 {
		t.Fatalf("stored stats = %+v, want all 0", got.Stats)
	}
}

func TestRepository_Update_ReplacesMediaAndKeepsStats(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC", Media: samplePostMedia()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if _, err := repository.AdjustLikeCount(ctx, postID, 1); err != nil {
		t.Fatalf("AdjustLikeCount() error = %v", err)
	}

	replacement := []usecase.PostMedia{{ID: uuid.New(), URL: "https://media.example.test/new.jpg", MediaType: usecase.PostMediaTypeImage, Order: 1}}
	if err := repository.Update(ctx, usecase.UpdatePostInput{PostID: postID, ExpectedVersion: 1, PostType: "BASIC", Media: replacement}); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	got := findStoredPost(t, ctx, db, postID)
	if len(got.Media) != 1 || got.Media[0].URL != "https://media.example.test/new.jpg" {
		t.Fatalf("stored media = %+v, want only the replacement", got.Media)
	}
	if got.Stats.LikeCount != 1 {
		t.Fatalf("stored like_count = %d, want 1 preserved by the update", got.Stats.LikeCount)
	}
}

func TestRepository_Update_LeavesMediaUntouchedWhenTheEventCarriedNone(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC", Media: samplePostMedia()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if err := repository.Update(ctx, usecase.UpdatePostInput{PostID: postID, ExpectedVersion: 1, PostType: "BASIC"}); err != nil {
		t.Fatalf("Update() error = %v", err)
	}

	if got := findStoredPost(t, ctx, db, postID); len(got.Media) != 2 {
		t.Fatalf("stored media length = %d, want 2 untouched", len(got.Media))
	}
}

func TestRepository_AdjustCounts_ChangeOnlyTheirCounterNeverGoBelowZeroAndKeepVersion(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if err := repository.Insert(ctx, usecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	steps := []struct {
		name   string
		adjust func(context.Context, uuid.UUID, int64) (bool, error)
		delta  int64
	}{
		{"like +1", repository.AdjustLikeCount, 1},
		{"like +1", repository.AdjustLikeCount, 1},
		{"like -1", repository.AdjustLikeCount, -1},
		{"comment +1", repository.AdjustCommentCount, 1},
		{"share -1 at zero", repository.AdjustShareCount, -1},
	}
	for _, step := range steps {
		found, err := step.adjust(ctx, postID, step.delta)
		if err != nil || !found {
			t.Fatalf("%s: found/error = %v/%v, want true/nil", step.name, found, err)
		}
	}

	got := findStoredPost(t, ctx, db, postID)
	if got.Stats.LikeCount != 1 || got.Stats.CommentCount != 1 || got.Stats.ShareCount != 0 {
		t.Fatalf("stored stats = %+v, want like 1, comment 1, share 0", got.Stats)
	}
	if got.Version != 1 {
		t.Fatalf("stored version = %d, want 1: counters must not conflict with PostUpdatedEvent", got.Version)
	}
}

func TestRepository_AdjustLikeCount_TreatsAPostWithoutStatsAsZero(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	db := testDatabase(t, ctx)
	repository := postmongo.NewRepository(db)

	postID := uuid.New()
	if _, err := db.Collection("posts").InsertOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "version", Value: int64(1)}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if _, err := repository.AdjustLikeCount(ctx, postID, 1); err != nil {
		t.Fatalf("AdjustLikeCount() error = %v", err)
	}

	if got := findStoredPost(t, ctx, db, postID); got.Stats.LikeCount != 1 {
		t.Fatalf("stored like_count = %d, want 1", got.Stats.LikeCount)
	}
}

func TestRepository_AdjustLikeCount_ReportsNotFoundWhenThePostIsNotProjected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	repository := postmongo.NewRepository(testDatabase(t, ctx))

	found, err := repository.AdjustLikeCount(ctx, uuid.New(), 1)
	if err != nil {
		t.Fatalf("AdjustLikeCount() error = %v", err)
	}
	if found {
		t.Fatal("AdjustLikeCount() found = true, want false for a post that is not projected")
	}
}
