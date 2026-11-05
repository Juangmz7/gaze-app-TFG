// Package usecase implements the use case that projects a
// UserRegisteredEvent into the user read model and asks for that user's first feed.
package usecase

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// RegisterUserInput is the data required to project a newly registered user.
type RegisterUserInput struct {
	UserID         uuid.UUID
	Username       string
	Email          string
	BioDescription string
	BioSocialMedia map[string]string
	CreatedAt      time.Time
	CorrelationID  string
}

// RegisterUserRepository persists the user read model. Implemented by
// user/infrastructure/mongo.Repository.
type RegisterUserRepository interface {
	Insert(ctx context.Context, input RegisterUserInput) error
}

// RegisterUserFeedPrefetcher asks for the user's first feed, so their first open finds
// it populated. A held lease is not an error. Implemented by
// feed/application/usecase.RequestFeedPrefetchUsecase.
type RegisterUserFeedPrefetcher interface {
	RequestUserRegisteredPrefetch(ctx context.Context, userID uuid.UUID, correlationID string) error
}

// RegisterUserUsecase projects a UserRegisteredEvent into the user read model.
type RegisterUserUsecase struct {
	repository RegisterUserRepository
	prefetcher RegisterUserFeedPrefetcher
}

// NewRegisterUser creates a RegisterUserUsecase backed by repository.
func NewRegisterUser(repository RegisterUserRepository, prefetcher RegisterUserFeedPrefetcher) *RegisterUserUsecase {
	return &RegisterUserUsecase{repository: repository, prefetcher: prefetcher}
}

// Execute inserts the user read model described by input, then requests the prefetch.
// A failed request is returned, because no sweeper covers a user with no presence: the
// redelivery finds the insert already applied and repeats only the request.
func (u *RegisterUserUsecase) Execute(ctx context.Context, input RegisterUserInput) error {
	if input.UserID == uuid.Nil {
		return fmt.Errorf("register user usecase: user id is required")
	}
	if input.Username == "" {
		return fmt.Errorf("register user usecase: username is required")
	}

	if err := u.repository.Insert(ctx, input); err != nil {
		return fmt.Errorf("register user usecase: %w", err)
	}

	if err := u.prefetcher.RequestUserRegisteredPrefetch(ctx, input.UserID, input.CorrelationID); err != nil {
		return fmt.Errorf("register user usecase: %w", err)
	}

	return nil
}
