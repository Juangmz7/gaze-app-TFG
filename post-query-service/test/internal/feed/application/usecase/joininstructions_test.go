package usecase_test

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

const harbourJoinInstructions = "Welcome! Chat: https://chat.example.test/invite/harbour"

// storedCollab builds a collab the way FindCollabs does, from a post_collabs document,
// so it carries its stored join instructions.
func storedCollab(collabID, postID, ownerID, instructions string) usecase.FeedCollab {
	return usecase.FeedCollabFromDocument(usecase.FeedCollabDocument{
		CollabID: collabID, PostID: postID, OwnerUserID: ownerID, Title: "Harbour", Status: "OPEN", JoinInstructions: instructions,
	})
}

func TestFeedCollabFromDocument_DoesNotExposeTheStoredJoinInstructions(t *testing.T) {
	collab := storedCollab("collab-1", "post-1", "owner-1", harbourJoinInstructions)

	if collab.JoinInstructions != nil {
		t.Fatalf("JoinInstructions = %q, want null until the viewer is found eligible", *collab.JoinInstructions)
	}
	card := usecase.FeedCardFromPost(samplePost(), usecase.FeedCreator{UserID: "creator-1"}, &collab, usecase.FeedViewerState{})
	if card.CollabJoinInstructions != nil {
		t.Fatalf("card built without readFeedState carries %q, want null", *card.CollabJoinInstructions)
	}
}

// Only the owner and an ACCEPTED member read the instructions; every other status and
// no membership at all read null.
func TestGetPostUsecase_Execute_ShowsJoinInstructionsOnlyToTheOwnerAndAcceptedMembers(t *testing.T) {
	owner := uuid.New()
	tests := []struct {
		name   string
		viewer uuid.UUID
		status string
		want   *string
	}{
		{name: "owner", viewer: owner, want: pointer(harbourJoinInstructions)},
		{name: "ACCEPTED member", viewer: uuid.New(), status: "ACCEPTED", want: pointer(harbourJoinInstructions)},
		{name: "PENDING requester", viewer: uuid.New(), status: "PENDING"},
		{name: "REJECTED requester", viewer: uuid.New(), status: "REJECTED"},
		{name: "DELETED request", viewer: uuid.New(), status: "DELETED"},
		{name: "member who LEFT", viewer: uuid.New(), status: "LEFT"},
		{name: "BANNED member", viewer: uuid.New(), status: "BANNED"},
		{name: "no membership", viewer: uuid.New()},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			store := newFakeFeedStore()
			postID := seedPostView(store)
			store.collabs = []usecase.FeedCollab{storedCollab("collab-1", postID.String(), owner.String(), harbourJoinInstructions)}
			if tt.status != "" {
				store.memberships = map[string][]usecase.FeedCollabMembership{tt.viewer.String(): {{CollabID: "collab-1", Status: tt.status}}}
			}

			card, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: tt.viewer, PostID: postID})
			if err != nil {
				t.Fatalf("Execute() error = %v", err)
			}
			assertJoinInstructions(t, card.CollabJoinInstructions, tt.want)
		})
	}
}

func TestGetPostUsecase_Execute_LeavesJoinInstructionsNullWhenTheCollabHasNone(t *testing.T) {
	owner := uuid.New()
	store := newFakeFeedStore()
	postID := seedPostView(store)
	store.collabs = []usecase.FeedCollab{storedCollab("collab-1", postID.String(), owner.String(), "")}

	card, err := usecase.NewGetPost(store, discardLogger()).Execute(context.Background(), usecase.GetPostInput{UserID: owner, PostID: postID})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	assertJoinInstructions(t, card.CollabJoinInstructions, nil)
}

// The claim decides per card, from the one batched membership lookup of the page.
func TestClaimFeedUsecase_Execute_ShowsJoinInstructionsPerCollabFromTheBatchedMemberships(t *testing.T) {
	viewer, owner := uuid.New(), uuid.New()
	store := newFakeFeedStore()
	postIDs := store.seed(0, 4)
	store.collabs = []usecase.FeedCollab{
		storedCollab("accepted", postIDs[0], owner.String(), "Accepted collab text"),
		storedCollab("pending", postIDs[1], owner.String(), "Pending collab text"),
		storedCollab("owned", postIDs[2], viewer.String(), "Own collab text"),
	}
	store.memberships = map[string][]usecase.FeedCollabMembership{
		viewer.String(): {{CollabID: "accepted", Status: "ACCEPTED"}, {CollabID: "pending", Status: "PENDING"}},
	}

	page := claim(t, store, usecase.ClaimFeedInput{UserID: viewer})

	want := []*string{pointer("Accepted collab text"), nil, pointer("Own collab text"), nil}
	for index, card := range page.Items {
		assertJoinInstructions(t, card.CollabJoinInstructions, want[index])
	}
	if store.calls["FindCollabMemberships"] != 1 {
		t.Fatalf("memberships calls = %d, want one batched lookup for the page", store.calls["FindCollabMemberships"])
	}
}

func TestFollowingFeedUsecase_Execute_ShowsJoinInstructionsToAnAcceptedMember(t *testing.T) {
	store := newFakeFollowingStore()
	viewer, stranger := uuid.New(), uuid.New()
	ana := store.projectUser("ana")
	store.follows[viewer.String()] = []string{ana}
	store.follows[stranger.String()] = []string{ana}
	post := store.postBy(ana, 1)
	store.collabs = []usecase.FeedCollab{storedCollab("collab-1", post.PostID, ana, harbourJoinInstructions)}
	store.memberships = map[string][]usecase.FeedCollabMembership{viewer.String(): {{CollabID: "collab-1", Status: "ACCEPTED"}}}
	following := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 10}, discardLogger())

	for _, tt := range []struct {
		name   string
		viewer uuid.UUID
		want   *string
	}{
		{name: "accepted member", viewer: viewer, want: pointer(harbourJoinInstructions)},
		{name: "stranger", viewer: stranger},
	} {
		page, err := following.Execute(context.Background(), usecase.FollowingFeedInput{UserID: tt.viewer, Limit: 20})
		if err != nil {
			t.Fatalf("%s: Execute() error = %v", tt.name, err)
		}
		if len(page.Items) != 1 {
			t.Fatalf("%s: items = %d, want 1", tt.name, len(page.Items))
		}
		assertJoinInstructions(t, page.Items[0].CollabJoinInstructions, tt.want)
	}
}

// The card's instructions reach collab.joinInstructions of both the feed item and the
// post view through the one mapper.
func TestFeedCardToResponse_CarriesTheCardsJoinInstructions(t *testing.T) {
	collab := &usecase.FeedCollab{CollabID: "collab-1", PostID: "post-1", Title: pointer("Harbour"), Status: pointer("OPEN"), JoinInstructions: pointer(harbourJoinInstructions)}
	card := usecase.FeedCardFromPost(samplePost(), usecase.FeedCreator{UserID: "creator-1", Username: "ana"}, collab, usecase.FeedViewerState{})

	page, err := usecase.FeedPageToResponse(context.Background(), usecase.ClaimFeedOutput{Items: []usecase.FeedCard{card}}, unsignedURLs{})
	if err != nil {
		t.Fatalf("FeedPageToResponse() error = %v", err)
	}
	for name, body := range map[string]map[string]any{"post view": toJSONMap(t, mustPostView(t, card)), "feed item": toJSONMap(t, page.Items[0])} {
		if got := body["collab"].(map[string]any)["joinInstructions"]; got != harbourJoinInstructions {
			t.Fatalf("%s collab.joinInstructions = %v, want %q", name, got, harbourJoinInstructions)
		}
	}

	post := samplePost()
	post.CollabID = nil
	basic := usecase.FeedCardFromPost(post, usecase.FeedCreator{UserID: "creator-1"}, nil, usecase.FeedViewerState{})
	if got, found := toJSONMap(t, mustPostView(t, basic))["collab"].(map[string]any)["joinInstructions"]; !found || got != nil {
		t.Fatalf("BASIC post collab.joinInstructions = %v (present %v), want present and null", got, found)
	}
}

func assertJoinInstructions(t *testing.T, got, want *string) {
	t.Helper()

	switch {
	case want == nil && got != nil:
		t.Fatalf("CollabJoinInstructions = %q, want null", *got)
	case want != nil && (got == nil || *got != *want):
		t.Fatalf("CollabJoinInstructions = %v, want %q", got, *want)
	}
}
