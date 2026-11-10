package usecase_test

import (
	"slices"
	"testing"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func TestClaimFeedUsecase_Execute_ServesTheMostRecentlyOpenedCollabOfAPost(t *testing.T) {
	opened := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	older := func(postID string) usecase.FeedCollab {
		return usecase.FeedCollab{CollabID: "collab-old", PostID: postID, Title: pointer("Old"), Status: pointer("CLOSED"), CreatedAt: opened}
	}
	newer := func(postID string) usecase.FeedCollab {
		return usecase.FeedCollab{CollabID: "collab-new", PostID: postID, Title: pointer("New"), Status: pointer("OPEN"), CreatedAt: opened.Add(time.Hour)}
	}

	cases := map[string]struct {
		postCollabID *string
		collabs      func(postID string) []usecase.FeedCollab
		wantID       string
		wantTitle    string
	}{
		"two collabs found by post id": {
			collabs: func(postID string) []usecase.FeedCollab { return []usecase.FeedCollab{older(postID), newer(postID)} },
			wantID:  "collab-new", wantTitle: "New",
		},
		"a stale posts.collab_id names the older one": {
			postCollabID: pointer("collab-old"),
			collabs:      func(postID string) []usecase.FeedCollab { return []usecase.FeedCollab{older(postID), newer(postID)} },
			wantID:       "collab-new", wantTitle: "New",
		},
		"posts.collab_id names a collab that is gone": {
			postCollabID: pointer("collab-gone"),
			collabs:      func(postID string) []usecase.FeedCollab { return []usecase.FeedCollab{newer(postID)} },
			wantID:       "collab-new", wantTitle: "New",
		},
		"a post linked to a newer collab of another post": {
			postCollabID: pointer("collab-new"),
			collabs: func(postID string) []usecase.FeedCollab {
				return []usecase.FeedCollab{older(postID), newer("another-post")}
			},
			wantID: "collab-new", wantTitle: "New",
		},
		"a post linked to a collab of another post, with none of its own": {
			postCollabID: pointer("collab-old"),
			collabs:      func(string) []usecase.FeedCollab { return []usecase.FeedCollab{older("another-post")} },
			wantID:       "collab-old", wantTitle: "Old",
		},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			// The answer must not depend on the order MongoDB returns the collabs in.
			for _, reversed := range []bool{false, true} {
				store := newFakeFeedStore()
				postIDs := store.seed(0, 1)
				post := store.posts[postIDs[0]]
				post.CollabID = tc.postCollabID
				store.posts[postIDs[0]] = post
				store.collabs = tc.collabs(postIDs[0])
				if reversed {
					slices.Reverse(store.collabs)
				}

				card := claim(t, store, usecase.ClaimFeedInput{}).Items[0]

				if card.CollabID == nil || *card.CollabID != tc.wantID || *card.CollabTitle != tc.wantTitle {
					t.Fatalf("reversed=%v: collab = %v %v, want %s %s", reversed, card.CollabID, card.CollabTitle, tc.wantID, tc.wantTitle)
				}
				if card.PostType != "COLLAB" {
					t.Fatalf("reversed=%v: postType = %s, want COLLAB", reversed, card.PostType)
				}
			}
		})
	}
}

func TestClaimFeedUsecase_Execute_BreaksATieBetweenCollabsByID(t *testing.T) {
	opened := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

	for _, reversed := range []bool{false, true} {
		store := newFakeFeedStore()
		postIDs := store.seed(0, 1)
		store.collabs = []usecase.FeedCollab{
			{CollabID: "collab-a", PostID: postIDs[0], CreatedAt: opened},
			{CollabID: "collab-b", PostID: postIDs[0], CreatedAt: opened},
		}
		if reversed {
			slices.Reverse(store.collabs)
		}

		card := claim(t, store, usecase.ClaimFeedInput{}).Items[0]

		if card.CollabID == nil || *card.CollabID != "collab-b" {
			t.Fatalf("reversed=%v: collab = %v, want collab-b", reversed, card.CollabID)
		}
	}
}
