package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// UpdatePostInput is the data required to apply a PostUpdatedEvent to an
// existing post read model document. It maps 1:1 to
// post/infrastructure/mongo.Repository.Update's parameter (the mongo
// package imports this type directly instead of declaring its own local
// update shape, the same relationship CreatePostInput has with Insert).
// ExpectedVersion is not set by the caller: PostUpdatedEvent carries no
// version/sequence number of its own, so Execute looks up the document's
// current version via UpdatePostRepository.GetVersion before calling
// Update, and fills it in.
type UpdatePostInput struct {
	PostID          uuid.UUID
	ExpectedVersion int64
	PostType        string
	Description     string
	Tags            []string
	UpdatedAt       time.Time
}

// UpdatePostRepository looks up the current version of a post read model
// document and applies the optimistic-concurrency update described by
// UpdatePostInput. Implemented by post/infrastructure/mongo.Repository.
type UpdatePostRepository interface {
	GetVersion(ctx context.Context, postID uuid.UUID) (version int64, found bool, err error)
	Update(ctx context.Context, input UpdatePostInput) error
}

// UpdatePostUsecase projects a PostUpdatedEvent into the post read model.
//
// Concurrency strategy: PostUpdatedEvent has no version of its own, so
// Execute reads the document's current version immediately before
// updating. If Update still reports a version conflict (another writer won
// the race between the read and the update) or GetVersion reports the post
// has no projection yet, Execute does not retry in-process: it wraps and
// returns the error as a plain (non-permanent) error, so the RabbitMQ retry
// middleware redelivers the message and a later attempt re-reads the
// by-then-fresh version.
type UpdatePostUsecase struct {
	repository UpdatePostRepository
}

// NewUpdatePost creates an UpdatePostUsecase backed by repository.
func NewUpdatePost(repository UpdatePostRepository) *UpdatePostUsecase {
	return &UpdatePostUsecase{repository: repository}
}

// Execute applies the post-updated projection described by input.
func (u *UpdatePostUsecase) Execute(ctx context.Context, input UpdatePostInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("update post usecase: post id is required")
	}

	version, found, err := u.repository.GetVersion(ctx, input.PostID)
	if err != nil {
		return fmt.Errorf("update post usecase: get current version: %w", err)
	}
	if !found {
		return fmt.Errorf("update post usecase: post %s has no projection yet", input.PostID)
	}
	input.ExpectedVersion = version

	if err := u.repository.Update(ctx, input); err != nil {
		return fmt.Errorf("update post usecase: %w", err)
	}

	return nil
}
