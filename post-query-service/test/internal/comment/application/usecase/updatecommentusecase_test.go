package usecase_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

type fakeUpdateCommentRepository struct {
	version       int64
	found         bool
	getVersionErr error
	updateCalls   int
	gotInput      usecase.UpdateCommentInput
	updateErr     error
	storedContent string
	storedUpdated time.Time
	storedFound   bool
	findErr       error
	findCalls     int
}

func (f *fakeUpdateCommentRepository) GetVersion(ctx context.Context, commentID uuid.UUID) (int64, bool, error) {
	return f.version, f.found, f.getVersionErr
}

func (f *fakeUpdateCommentRepository) Update(ctx context.Context, input usecase.UpdateCommentInput) error {
	f.updateCalls++
	f.gotInput = input
	return f.updateErr
}

func (f *fakeUpdateCommentRepository) FindContentAndUpdatedAt(ctx context.Context, commentID uuid.UUID) (string, time.Time, bool, error) {
	f.findCalls++
	return f.storedContent, f.storedUpdated, f.storedFound, f.findErr
}

func testUpdateCommentLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func validUpdateCommentInput() usecase.UpdateCommentInput {
	return usecase.UpdateCommentInput{
		CommentID: uuid.New(),
		PostID:    uuid.New(),
		UserID:    uuid.New(),
		Content:   "edited content",
		UpdatedAt: time.Now().UTC(),
	}
}

func TestUpdateCommentUsecase_Execute_UpdatesTheCommentReadModelWithTheCurrentVersion(t *testing.T) {
	repository := &fakeUpdateCommentRepository{version: 1, found: true}
	uc := usecase.NewUpdateComment(repository, testUpdateCommentLogger())

	input := validUpdateCommentInput()
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.updateCalls != 1 {
		t.Fatalf("Update() calls = %d, want 1", repository.updateCalls)
	}
	if repository.gotInput.ExpectedVersion != 1 {
		t.Fatalf("Update() ExpectedVersion = %d, want 1 (from GetVersion)", repository.gotInput.ExpectedVersion)
	}
}

func TestUpdateCommentUsecase_Execute_ReturnsErrorWhenCommentIDIsMissing(t *testing.T) {
	uc := usecase.NewUpdateComment(&fakeUpdateCommentRepository{}, testUpdateCommentLogger())

	input := validUpdateCommentInput()
	input.CommentID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when comment id is missing")
	}
}

func TestUpdateCommentUsecase_Execute_ReturnsErrorWhenTheCommentHasNoProjectionYet(t *testing.T) {
	repository := &fakeUpdateCommentRepository{found: false}
	uc := usecase.NewUpdateComment(repository, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), validUpdateCommentInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error when the comment was never projected")
	}
	if repository.updateCalls != 0 {
		t.Fatalf("Update() calls = %d, want 0 when GetVersion reports not found", repository.updateCalls)
	}
}

func TestUpdateCommentUsecase_Execute_PropagatesTheGetVersionError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	uc := usecase.NewUpdateComment(&fakeUpdateCommentRepository{getVersionErr: wantErr}, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), validUpdateCommentInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestUpdateCommentUsecase_Execute_PropagatesANonConflictUpdateError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewUpdateComment(&fakeUpdateCommentRepository{version: 1, found: true, updateErr: wantErr}, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), validUpdateCommentInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

// TestUpdateCommentUsecase_Execute_AcksOnAVersionConflictThatIsActuallyAnAlreadyAppliedRedelivery
// proves the safety net compensating for CommentUpdatedEvent carrying no
// event id: a version conflict whose stored content/updatedAt already
// equal the incoming event's is treated as a harmless redelivery, not a
// genuine conflict.
func TestUpdateCommentUsecase_Execute_AcksOnAVersionConflictThatIsActuallyAnAlreadyAppliedRedelivery(t *testing.T) {
	input := validUpdateCommentInput()
	repository := &fakeUpdateCommentRepository{
		version:       1,
		found:         true,
		updateErr:     usecase.ErrCommentVersionConflict,
		storedContent: input.Content,
		storedUpdated: input.UpdatedAt,
		storedFound:   true,
	}
	uc := usecase.NewUpdateComment(repository, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil for a harmless redelivery", err)
	}
	if repository.findCalls != 1 {
		t.Fatalf("FindContentAndUpdatedAt() calls = %d, want 1", repository.findCalls)
	}
}

// TestUpdateCommentUsecase_Execute_PropagatesAGenuineVersionConflict proves
// the safety net does not swallow a real conflict: when the stored fields
// differ from the incoming event, Execute still returns the error so the
// message is redelivered.
func TestUpdateCommentUsecase_Execute_PropagatesAGenuineVersionConflict(t *testing.T) {
	input := validUpdateCommentInput()
	repository := &fakeUpdateCommentRepository{
		version:       1,
		found:         true,
		updateErr:     usecase.ErrCommentVersionConflict,
		storedContent: "a different, newer edit",
		storedUpdated: input.UpdatedAt.Add(time.Minute),
		storedFound:   true,
	}
	uc := usecase.NewUpdateComment(repository, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), input); !errors.Is(err, usecase.ErrCommentVersionConflict) {
		t.Fatalf("Execute() error = %v, want it to wrap ErrCommentVersionConflict", err)
	}
}

func TestUpdateCommentUsecase_Execute_PropagatesTheFindContentError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	input := validUpdateCommentInput()
	repository := &fakeUpdateCommentRepository{
		version:   1,
		found:     true,
		updateErr: usecase.ErrCommentVersionConflict,
		findErr:   wantErr,
	}
	uc := usecase.NewUpdateComment(repository, testUpdateCommentLogger())

	if err := uc.Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
