package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// UpdateUserInput is the data required to apply a UserUpdatedEvent to an
// existing user read model document. It maps 1:1 to
// user/infrastructure/mongo.Repository.Update's parameter (the mongo
// package imports this type directly instead of declaring its own local
// update shape, the same relationship RegisterUserInput has with Insert).
// ExpectedVersion is not set by the caller: UserUpdatedEvent carries no
// version/sequence number of its own, so Execute looks up the document's
// current version via UpdateUserRepository.GetVersion before calling
// Update, and fills it in.
type UpdateUserInput struct {
	UserID          uuid.UUID
	ExpectedVersion int64
	Username        string
	Email           string
	BioDescription  string
	BioSocialMedia  map[string]string
	PictureURL      string
	AccountStatus   string
	UpdatedAt       time.Time
}

// UpdateUserRepository looks up the current version of a user read model
// document and applies the optimistic-concurrency update described by
// UpdateUserInput. Implemented by user/infrastructure/mongo.Repository.
type UpdateUserRepository interface {
	GetVersion(ctx context.Context, userID uuid.UUID) (version int64, found bool, err error)
	Update(ctx context.Context, input UpdateUserInput) error
}

// UpdateUserUsecase projects a UserUpdatedEvent into the user read model.
//
// Concurrency strategy: UserUpdatedEvent has no version of its own, so
// Execute reads the document's current version immediately before
// updating. If Update still reports a version conflict (another writer won
// the race between the read and the update), Execute does not retry
// in-process: it wraps and returns the error as a plain (non-permanent)
// error, so the RabbitMQ retry middleware redelivers the message and a
// later attempt re-reads the by-then-fresh version.
type UpdateUserUsecase struct {
	repository UpdateUserRepository
}

// NewUpdateUser creates an UpdateUserUsecase backed by repository.
func NewUpdateUser(repository UpdateUserRepository) *UpdateUserUsecase {
	return &UpdateUserUsecase{repository: repository}
}

// Execute applies the user-updated projection described by input.
func (u *UpdateUserUsecase) Execute(ctx context.Context, input UpdateUserInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("update user usecase: user id is required")
	}
	if input.Username == "" {
		return fmt.Errorf("update user usecase: username is required")
	}

	version, found, err := u.repository.GetVersion(ctx, input.UserID)
	if err != nil {
		return fmt.Errorf("update user usecase: get current version: %w", err)
	}
	if !found {
		return fmt.Errorf("update user usecase: user %s has no projection yet", input.UserID)
	}
	input.ExpectedVersion = version

	if err := u.repository.Update(ctx, input); err != nil {
		return fmt.Errorf("update user usecase: %w", err)
	}

	return nil
}
