package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

type getPostContextKey struct{}

// seedPostView stores one post by creator-1 in the fake read models and returns its id.
func seedPostView(store *fakeFeedStore) uuid.UUID {
	postID := uuid.New()
	description := "Harbour at dusk"
	store.posts[postID.String()] = usecase.FeedPost{
		PostID:      postID.String(),
		CreatorID:   "creator-1",
		PostType:    usecase.FeedPostTypeBasic,
		Description: &description,
		Tags:        []string{"travel"},
		Media:       []usecase.FeedMedia{{ID: "m1", URL: "a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 1}},
		Stats:       usecase.FeedStats{LikeCount: 4},
		CreatedAt:   time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
	}
	return postID
}

func TestGetPostUsecase_Execute_ReturnsThePostWithTheViewerStateOfTheCaller(t *testing.T) {
	store := newFakeFeedStore()
	postID := seedPostView(store)
	store.liked = []string{postID.String()}
	store.followed = []string{"creator-1"}
	collabTitle, collabStatus := "Harbour", "OPEN"
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: postID.String(), Title: &collabTitle, Status: &collabStatus}}

	card, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: uuid.New(), PostID: postID})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if card.PostID != postID.String() || card.Creator.Username != "ana" || *card.Description != "Harbour at dusk" {
		t.Fatalf("card = %+v", card)
	}
	if card.Viewer != (usecase.FeedViewerState{Liked: true, Shared: false, FollowingCreator: true}) {
		t.Fatalf("viewer = %+v, want liked and following, not shared", card.Viewer)
	}
	if card.PostType != usecase.FeedPostTypeCollab || card.CollabID == nil || *card.CollabID != "collab-1" || *card.CollabTitle != "Harbour" {
		t.Fatalf("card = %+v, want the attached collab", card)
	}
	if !card.CreatedAt.Equal(time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)) || card.Stats.LikeCount != 4 {
		t.Fatalf("createdAt = %v, stats = %+v", card.CreatedAt, card.Stats)
	}
	if card.CoverThumbnailURL == nil || *card.CoverThumbnailURL != "a.jpg" {
		t.Fatalf("coverThumbnailUrl = %v, want the derived cover", card.CoverThumbnailURL)
	}
}

func TestGetPostUsecase_Execute_ReadsEachCollectionOnceAndNoFeed(t *testing.T) {
	store := newFakeFeedStore()
	postID := seedPostView(store)

	if _, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: uuid.New(), PostID: postID}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	for _, name := range []string{"FindPosts", "FindCreators", "FindCollabs", "FindLikedPostIDs", "FindSharedPostIDs", "FindFollowedUserIDs", "FindBlockRelatedUserIDs"} {
		if store.calls[name] != 1 {
			t.Fatalf("%s calls = %d, want 1", name, store.calls[name])
		}
	}
	for _, name := range []string{"FindAfterPosition", "MarkServed", "FindPosition"} {
		if store.calls[name] != 0 {
			t.Fatalf("%s calls = %d, want 0: reading a post touches no feed", name, store.calls[name])
		}
	}
}

func TestGetPostUsecase_Execute_ReturnsNotFoundWhenTheCallerMayNotSeeThePost(t *testing.T) {
	cases := map[string]func(store *fakeFeedStore, postID uuid.UUID){
		"missing, deleted or banned post": func(store *fakeFeedStore, postID uuid.UUID) { delete(store.posts, postID.String()) },
		"creator gone":                    func(store *fakeFeedStore, _ uuid.UUID) { delete(store.creators, "creator-1") },
		"blocked creator":                 func(store *fakeFeedStore, _ uuid.UUID) { store.blocked = []string{"creator-1"} },
	}

	for name, hide := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeFeedStore()
			postID := seedPostView(store)
			hide(store, postID)

			_, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: uuid.New(), PostID: postID})

			if !errors.Is(err, usecase.ErrGetPostNotFound) {
				t.Fatalf("Execute() error = %v, want ErrGetPostNotFound", err)
			}
		})
	}
}

func TestGetPostUsecase_Execute_IgnoresAPostTheRepositoryReturnedThatWasNotAskedFor(t *testing.T) {
	store := newFakeFeedStore()
	other := seedPostView(store)
	asked := uuid.New()
	// The post stored under the asked key names another id.
	store.posts[asked.String()] = store.posts[other.String()]

	_, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: uuid.New(), PostID: asked})

	if !errors.Is(err, usecase.ErrGetPostNotFound) {
		t.Fatalf("Execute() error = %v, want ErrGetPostNotFound", err)
	}
}

func TestGetPostUsecase_Execute_RejectsMissingIDs(t *testing.T) {
	cases := map[string]usecase.GetPostInput{
		"no user": {PostID: uuid.New()},
		"no post": {UserID: uuid.New()},
	}

	for name, input := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeFeedStore()
			_, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), input)

			if err == nil || errors.Is(err, usecase.ErrGetPostNotFound) {
				t.Fatalf("Execute() error = %v, want a validation error", err)
			}
			if store.calls["FindPosts"] != 0 {
				t.Fatal("the repository was read for invalid input")
			}
		})
	}
}

func TestGetPostUsecase_Execute_PropagatesTheContextAndEveryFailure(t *testing.T) {
	for _, name := range []string{"FindPosts", "FindCreators", "FindCollabs", "FindLikedPostIDs", "FindSharedPostIDs", "FindFollowedUserIDs", "FindBlockRelatedUserIDs"} {
		t.Run(name, func(t *testing.T) {
			store := newFakeFeedStore()
			postID := seedPostView(store)
			store.failOn, store.failErr = name, errors.New("mongo unavailable")
			ctx := context.WithValue(context.Background(), getPostContextKey{}, "request")

			_, err := usecase.NewGetPost(store, discardLogger()).Execute(ctx, usecase.GetPostInput{UserID: uuid.New(), PostID: postID})

			if !errors.Is(err, store.failErr) || errors.Is(err, usecase.ErrGetPostNotFound) {
				t.Fatalf("Execute() error = %v, want the %s failure and not a not-found", err, name)
			}
			for _, got := range store.ctxs {
				if got.Value(getPostContextKey{}) != "request" {
					t.Fatal("a repository call did not receive the request context")
				}
			}
		})
	}
}
