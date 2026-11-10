package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

type fakeDeletePostRepository struct {
	deleteCalls int
	gotPostID   uuid.UUID
	err         error
}

func (f *fakeDeletePostRepository) Delete(ctx context.Context, postID uuid.UUID) error {
	f.deleteCalls++
	f.gotPostID = postID
	return f.err
}

type fakeDeletePostFeedRepository struct {
	deleteCalls int
	gotPostID   uuid.UUID
	err         error
}

func (f *fakeDeletePostFeedRepository) DeleteByPost(ctx context.Context, postID uuid.UUID) error {
	f.deleteCalls++
	f.gotPostID = postID
	return f.err
}

func TestDeletePostUsecase_Execute_DeletesThePostReadModelForAValidInput(t *testing.T) {
	repository := &fakeDeletePostRepository{}
	feedUnits := &fakeDeletePostFeedRepository{}
	uc := usecase.NewDeletePost(repository, feedUnits)

	postID := uuid.New()

	if err := uc.Execute(context.Background(), usecase.DeletePostInput{PostID: postID}); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.deleteCalls != 1 {
		t.Fatalf("Delete() calls = %d, want 1", repository.deleteCalls)
	}
	if repository.gotPostID != postID {
		t.Fatalf("Delete() postID = %v, want %v", repository.gotPostID, postID)
	}
	if feedUnits.deleteCalls != 1 || feedUnits.gotPostID != postID {
		t.Fatalf("DeleteByPost() calls = %d, postID = %v, want 1 call for %v", feedUnits.deleteCalls, feedUnits.gotPostID, postID)
	}
}

func TestDeletePostUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewDeletePost(&fakeDeletePostRepository{}, &fakeDeletePostFeedRepository{})

	if err := uc.Execute(context.Background(), usecase.DeletePostInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestDeletePostUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeletePost(&fakeDeletePostRepository{err: wantErr}, &fakeDeletePostFeedRepository{})

	err := uc.Execute(context.Background(), usecase.DeletePostInput{PostID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestDeletePostUsecase_Execute_PropagatesTheFeedRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo delete failed")
	uc := usecase.NewDeletePost(&fakeDeletePostRepository{}, &fakeDeletePostFeedRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.DeletePostInput{PostID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
