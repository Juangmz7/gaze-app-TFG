// Package mongo implements user/application/usecase ports against MongoDB.
package mongo

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/application/usecase"
)

// collectionName is the MongoDB collection backing the user read model.
const collectionName = "users"

// operationTimeout bounds every Mongo call made by Repository.
const operationTimeout = 5 * time.Second

// ErrVersionConflict is returned by Update when the stored document's
// version does not match UpdateInput.ExpectedVersion, meaning the document
// was modified by another writer since the version was read.
var ErrVersionConflict = errors.New("user version conflict")

// userDocument is the persisted shape of a user read model entry. Email,
// BioDescription, BioSocialMedia, PictureURL, AccountStatus, and UpdatedAt
// are populated once a UserUpdatedEvent is projected (see Update); the
// register-user Insert path leaves them at their Go zero values, since
// UserRegisteredEvent does not carry them.
type userDocument struct {
	UserID         string            `bson:"user_id"`
	Username       string            `bson:"username"`
	Email          string            `bson:"email"`
	BioDescription string            `bson:"bio_description"`
	BioSocialMedia map[string]string `bson:"bio_social_media"`
	PictureURL     string            `bson:"picture_url"`
	AccountStatus  string            `bson:"account_status"`
	CreatedAt      time.Time         `bson:"created_at"`
	UpdatedAt      time.Time         `bson:"updated_at"`
	Version        int64             `bson:"version"`
}

// Repository implements usecase.RegisterUserRepository and
// usecase.DeleteUserRepository against MongoDB.
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a Repository backed by db's users collection.
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{collection: db.Collection(collectionName)}
}

// EnsureIndexes creates the unique index on user_id required to make
// Insert idempotent under concurrent or retried deliveries of the same
// creation event.
func (r *Repository) EnsureIndexes(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	indexModel := mongo.IndexModel{
		Keys:    bson.D{{Key: "user_id", Value: 1}},
		Options: options.Index().SetUnique(true),
	}

	if _, err := r.collection.Indexes().CreateOne(ctx, indexModel); err != nil {
		return fmt.Errorf("ensure users indexes: %w", err)
	}

	return nil
}

// Insert writes input as a new user read model document at version 1. A
// duplicate delivery of the same user-registered event is already filtered
// out upstream by the idempotency repository before the use case runs, but
// a crash between MarkProcessed and this write makes a retry of the same
// creation event theoretically possible; when that retry hits the unique
// index on user_id, Insert treats the duplicate-key error as an
// already-applied insert and returns nil.
func (r *Repository) Insert(ctx context.Context, input usecase.RegisterUserInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	doc := userDocument{
		UserID:    input.UserID.String(),
		Username:  input.Username,
		CreatedAt: input.CreatedAt,
		Version:   1,
	}

	if _, err := r.collection.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil
		}
		return fmt.Errorf("insert user %s: %w", input.UserID, err)
	}

	return nil
}

// GetVersion returns the current version of the user read model document
// identified by userID, and whether it exists at all. UpdateUserUsecase
// uses it to fill in UpdateUserInput.ExpectedVersion before calling
// Update, since UserUpdatedEvent carries no version of its own.
func (r *Repository) GetVersion(ctx context.Context, userID uuid.UUID) (int64, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var doc struct {
		Version int64 `bson:"version"`
	}
	err := r.collection.FindOne(ctx, bson.D{{Key: "user_id", Value: userID.String()}}).Decode(&doc)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return 0, false, nil
		}
		return 0, false, fmt.Errorf("get user %s version: %w", userID, err)
	}

	return doc.Version, true, nil
}

// Update applies an optimistic-concurrency update to the user read model
// document identified by input.UserID. It only succeeds when the stored
// document's version equals input.ExpectedVersion; on success the changed
// fields are set and the version is incremented by one. If no document
// matched (either the user does not exist or its version has already moved
// on), Update returns ErrVersionConflict.
func (r *Repository) Update(ctx context.Context, input usecase.UpdateUserInput) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{
		{Key: "user_id", Value: input.UserID.String()},
		{Key: "version", Value: input.ExpectedVersion},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "username", Value: input.Username},
			{Key: "email", Value: input.Email},
			{Key: "bio_description", Value: input.BioDescription},
			{Key: "bio_social_media", Value: input.BioSocialMedia},
			{Key: "picture_url", Value: input.PictureURL},
			{Key: "account_status", Value: input.AccountStatus},
			{Key: "updated_at", Value: input.UpdatedAt},
		}},
		{Key: "$inc", Value: bson.D{{Key: "version", Value: int64(1)}}},
	}

	result, err := r.collection.UpdateOne(ctx, filter, update)
	if err != nil {
		return fmt.Errorf("update user %s: %w", input.UserID, err)
	}
	if result.ModifiedCount == 0 {
		return fmt.Errorf("update user %s: %w", input.UserID, ErrVersionConflict)
	}

	return nil
}

// Delete idempotently removes userID's user read model document. Deleting an
// already-absent document is not an error.
func (r *Repository) Delete(ctx context.Context, userID uuid.UUID) error {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	filter := bson.D{{Key: "user_id", Value: userID.String()}}
	if _, err := r.collection.DeleteOne(ctx, filter); err != nil {
		return fmt.Errorf("delete user %s: %w", userID, err)
	}

	return nil
}
