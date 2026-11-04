package usecase_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

type fakeCreatePostRepository struct {
	calls int
	got   usecase.CreatePostInput
	err   error
}

func (f *fakeCreatePostRepository) Insert(ctx context.Context, input usecase.CreatePostInput) error {
	f.calls++
	f.got = input
	return f.err
}

func validPostMedia() usecase.PostMedia {
	return usecase.PostMedia{
		ID:        uuid.New(),
		URL:       "https://media.example.test/sunset.jpg",
		MediaType: usecase.PostMediaTypeImage,
		Order:     1,
	}
}

func TestCreatePostUsecase_Execute_PassesMediaToTheRepository(t *testing.T) {
	repository := &fakeCreatePostRepository{}
	uc := usecase.NewCreatePost(repository)
	media := validPostMedia()

	input := usecase.CreatePostInput{PostID: uuid.New(), UserID: uuid.New(), Media: []usecase.PostMedia{media}}
	if err := uc.Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v, want nil", err)
	}

	if len(repository.got.Media) != 1 || repository.got.Media[0].ID != media.ID {
		t.Fatalf("Insert() Media = %+v, want the input's media", repository.got.Media)
	}
}

func TestCreatePostUsecase_Execute_RejectsInvalidMediaWithoutWriting(t *testing.T) {
	negative := int64(-1)
	tests := []struct {
		name   string
		mutate func(*usecase.PostMedia)
	}{
		{name: "missing id", mutate: func(m *usecase.PostMedia) { m.ID = uuid.Nil }},
		{name: "blank url", mutate: func(m *usecase.PostMedia) { m.URL = "  " }},
		{name: "unknown media type", mutate: func(m *usecase.PostMedia) { m.MediaType = "GIF" }},
		{name: "zero-based order", mutate: func(m *usecase.PostMedia) { m.Order = 0 }},
		{name: "negative duration", mutate: func(m *usecase.PostMedia) { m.Duration = &negative }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repository := &fakeCreatePostRepository{}
			uc := usecase.NewCreatePost(repository)
			media := validPostMedia()
			tt.mutate(&media)

			input := usecase.CreatePostInput{PostID: uuid.New(), UserID: uuid.New(), Media: []usecase.PostMedia{media}}
			if err := uc.Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want an error for invalid media")
			}
			if repository.calls != 0 {
				t.Fatalf("Insert() calls = %d, want 0", repository.calls)
			}
		})
	}
}

func TestCreatePostUsecase_Execute_PropagatesTheRepositoryError(t *testing.T) {
	wantErr := errors.New("mongo write failed")
	uc := usecase.NewCreatePost(&fakeCreatePostRepository{err: wantErr})

	err := uc.Execute(context.Background(), usecase.CreatePostInput{PostID: uuid.New(), UserID: uuid.New()})
	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
	}
}
