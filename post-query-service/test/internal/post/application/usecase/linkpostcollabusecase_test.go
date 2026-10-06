package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

type fakeLinkPostCollabRepository struct {
	version      int64
	found        bool
	getVersionEr error
	linkCalls    int
	gotInput     usecase.LinkPostCollabInput
	linkErr      error
}

func (f *fakeLinkPostCollabRepository) GetVersion(ctx context.Context, postID uuid.UUID) (int64, bool, error) {
	return f.version, f.found, f.getVersionEr
}

func (f *fakeLinkPostCollabRepository) LinkCollab(ctx context.Context, input usecase.LinkPostCollabInput) error {
	f.linkCalls++
	f.gotInput = input
	return f.linkErr
}

func validLinkPostCollabInput() usecase.LinkPostCollabInput {
	return usecase.LinkPostCollabInput{
		PostID:   uuid.New(),
		CollabID: uuid.New(),
	}
}

func TestLinkPostCollabUsecase_Execute_LinksTheCollabWithTheCurrentVersion(t *testing.T) {
	repository := &fakeLinkPostCollabRepository{version: 3, found: true}
	uc := usecase.NewLinkPostCollab(repository)

	input := validLinkPostCollabInput()
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if repository.linkCalls != 1 {
		t.Fatalf("LinkCollab() calls = %d, want 1", repository.linkCalls)
	}
	if repository.gotInput.ExpectedVersion != 3 {
		t.Fatalf("LinkCollab() ExpectedVersion = %d, want 3 (from GetVersion)", repository.gotInput.ExpectedVersion)
	}
	if repository.gotInput.PostID != input.PostID {
		t.Fatalf("LinkCollab() PostID = %v, want %v", repository.gotInput.PostID, input.PostID)
	}
	if repository.gotInput.CollabID != input.CollabID {
		t.Fatalf("LinkCollab() CollabID = %v, want %v", repository.gotInput.CollabID, input.CollabID)
	}
}

func TestLinkPostCollabUsecase_Execute_ReturnsErrorWhenPostIDIsMissing(t *testing.T) {
	uc := usecase.NewLinkPostCollab(&fakeLinkPostCollabRepository{})

	input := validLinkPostCollabInput()
	input.PostID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when post id is missing")
	}
}

func TestLinkPostCollabUsecase_Execute_ReturnsErrorWhenCollabIDIsMissing(t *testing.T) {
	uc := usecase.NewLinkPostCollab(&fakeLinkPostCollabRepository{})

	input := validLinkPostCollabInput()
	input.CollabID = uuid.Nil

	if err := uc.Execute(context.Background(), input); err == nil {
		t.Fatal("Execute() error = nil, want an error when collab id is missing")
	}
}

func TestLinkPostCollabUsecase_Execute_ReturnsErrorWhenThePostHasNoProjectionYet(t *testing.T) {
	repository := &fakeLinkPostCollabRepository{found: false}
	uc := usecase.NewLinkPostCollab(repository)

	if err := uc.Execute(context.Background(), validLinkPostCollabInput()); err == nil {
		t.Fatal("Execute() error = nil, want an error when the post was never projected")
	}
	if repository.linkCalls != 0 {
		t.Fatalf("LinkCollab() calls = %d, want 0 when GetVersion reports not found", repository.linkCalls)
	}
}

func TestLinkPostCollabUsecase_Execute_PropagatesTheGetVersionError(t *testing.T) {
	wantErr := errors.New("mongo read failed")
	uc := usecase.NewLinkPostCollab(&fakeLinkPostCollabRepository{getVersionEr: wantErr})

	if err := uc.Execute(context.Background(), validLinkPostCollabInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}

func TestLinkPostCollabUsecase_Execute_PropagatesTheLinkCollabError(t *testing.T) {
	wantErr := errors.New("version conflict")
	uc := usecase.NewLinkPostCollab(&fakeLinkPostCollabRepository{version: 1, found: true, linkErr: wantErr})

	if err := uc.Execute(context.Background(), validLinkPostCollabInput()); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
