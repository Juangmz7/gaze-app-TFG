package usecase

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// LinkPostCollabInput is the data required to apply a CollabLinkedEvent to
// an existing post read model document. It maps 1:1 to
// post/infrastructure/mongo.Repository.LinkCollab's parameter (the mongo
// package imports this type directly instead of declaring its own local
// update shape, the same relationship UpdatePostInput has with Update).
// ExpectedVersion is not set by the caller: CollabLinkedEvent carries no
// version/sequence number of its own, so Execute looks up the document's
// current version via LinkPostCollabRepository.GetVersion before calling
// LinkCollab, and fills it in.
type LinkPostCollabInput struct {
	PostID          uuid.UUID
	CollabID        uuid.UUID
	ExpectedVersion int64
}

// LinkPostCollabRepository looks up the current version of a post read
// model document and applies the optimistic-concurrency collab link
// described by LinkPostCollabInput. Implemented by
// post/infrastructure/mongo.Repository.
type LinkPostCollabRepository interface {
	GetVersion(ctx context.Context, postID uuid.UUID) (version int64, found bool, err error)
	LinkCollab(ctx context.Context, input LinkPostCollabInput) error
}

// LinkPostCollabUsecase projects a CollabLinkedEvent into the post read
// model. CollabLinkedEvent carries only the relational fact that an
// already-existing post was linked into a collaboration; it does not carry
// any post content, so this use case writes only collab_id onto the
// already-projected post, never content fields.
//
// Concurrency strategy: CollabLinkedEvent has no version of its own, so
// Execute reads the document's current version immediately before
// updating. If LinkCollab still reports a version conflict (another writer
// won the race between the read and the update) or GetVersion reports the
// post has no projection yet (PostCreatedEvent for this post has not been
// projected yet, which can race with CollabLinkedEvent since both are
// published independently), Execute does not retry in-process: it wraps and
// returns the error as a plain (non-permanent) error, so the RabbitMQ retry
// middleware redelivers the message and a later attempt re-reads the
// by-then-fresh version.
type LinkPostCollabUsecase struct {
	repository LinkPostCollabRepository
}

// NewLinkPostCollab creates a LinkPostCollabUsecase backed by repository.
func NewLinkPostCollab(repository LinkPostCollabRepository) *LinkPostCollabUsecase {
	return &LinkPostCollabUsecase{repository: repository}
}

// Execute applies the post-collab-linked projection described by input.
func (u *LinkPostCollabUsecase) Execute(ctx context.Context, input LinkPostCollabInput) error {
	if input.PostID == uuid.Nil {
		return fmt.Errorf("link post collab usecase: post id is required")
	}
	if input.CollabID == uuid.Nil {
		return fmt.Errorf("link post collab usecase: collab id is required")
	}

	version, found, err := u.repository.GetVersion(ctx, input.PostID)
	if err != nil {
		return fmt.Errorf("link post collab usecase: get current version: %w", err)
	}
	if !found {
		return fmt.Errorf("link post collab usecase: post %s has no projection yet", input.PostID)
	}
	input.ExpectedVersion = version

	if err := u.repository.LinkCollab(ctx, input); err != nil {
		return fmt.Errorf("link post collab usecase: %w", err)
	}

	return nil
}
