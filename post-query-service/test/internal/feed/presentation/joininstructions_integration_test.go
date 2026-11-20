package presentation_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"

	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
)

const (
	openingJoinInstructions = "Welcome aboard! Chat: https://chat.example.test/invite/harbour"
	updatedJoinInstructions = "We moved to https://notion.example.test/harbour-onboarding"
)

// openCollabWithInstructions projects a CollabOpenedEvent carrying join instructions
// through the real usecase and repository, as PostCollabOpenedHandler does.
func (a *feedApp) openCollabWithInstructions(t *testing.T, ctx context.Context, owner, postID uuid.UUID, instructions string, occurredAt time.Time) uuid.UUID {
	t.Helper()

	collabs, members := collabmongo.NewRepository(a.db), collabmongo.NewMemberRepository(a.db)
	for _, ensure := range []func(context.Context) error{collabs.EnsureIndexes, members.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}
	collabID := uuid.New()
	err := collabusecase.NewRecordCollabOpened(collabs).Execute(ctx, collabusecase.RecordCollabOpenedInput{
		CollabID: collabID, PostID: postID, OwnerUserID: owner, Title: "Harbour series", Status: "OPEN",
		CreatedAt: occurredAt, JoinInstructions: instructions, OccurredAt: occurredAt,
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	return collabID
}

// updateJoinInstructions projects a CollabJoinInstructionsUpdatedEvent through the real
// usecase and repository, as CollabJoinInstructionsUpdatedHandler does.
func (a *feedApp) updateJoinInstructions(t *testing.T, ctx context.Context, collabID uuid.UUID, instructions string, occurredAt time.Time) bool {
	t.Helper()

	applied, err := collabusecase.NewUpdateCollabJoinInstructions(collabmongo.NewRepository(a.db)).Execute(ctx, collabusecase.UpdateCollabJoinInstructionsInput{
		CollabID: collabID, JoinInstructions: instructions, OccurredAt: occurredAt,
	})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	return applied
}

// joinInstructionsOf reads collab.joinInstructions off a feed item or a post view,
// failing when the key is missing: it is always present, null or not.
func joinInstructionsOf(t *testing.T, item map[string]any) any {
	t.Helper()

	collab, ok := item["collab"].(map[string]any)
	if !ok {
		t.Fatalf("item has no collab: %v", item)
	}
	instructions, found := collab["joinInstructions"]
	if !found {
		t.Fatalf("collab = %v, want joinInstructions present, null or not", collab)
	}
	return instructions
}

// Acceptance: an ACCEPTED member and the owner read joinInstructions on the feed item,
// the post view and the following feed, from the opening event and then from the next
// update; a PENDING caller and a stranger read null.
func TestJoinInstructions_OnlyTheOwnerAndAcceptedMembersReadThem(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	world := newFollowingWorld(t, ctx)
	owner := world.creator
	postIDs := world.seedPosts(t, ctx, 2)
	first, second := uuid.MustParse(postIDs[0]), uuid.MustParse(postIDs[1])
	collabID := world.openCollabWithInstructions(t, ctx, owner, first, openingJoinInstructions, commentsEpoch)
	// The second post names the collab, so both units of the viewer's feed resolve it.
	if _, err := world.db.Collection("posts").UpdateOne(ctx, bson.D{{Key: "post_id", Value: postIDs[1]}}, bson.D{{Key: "$set", Value: bson.D{{Key: "collab_id", Value: collabID.String()}}}}); err != nil {
		t.Fatalf("UpdateOne() error = %v", err)
	}
	pending, stranger := world.registerUser(t, ctx, "bruno"), world.registerUser(t, ctx, "carla")
	for _, follower := range []uuid.UUID{world.viewerID, pending, stranger} {
		world.follow(t, ctx, follower, owner, commentsEpoch)
	}
	world.applyMember(t, ctx, collabID, world.viewerID, "PENDING", commentsEpoch, commentsEpoch.Add(time.Minute))
	world.applyMember(t, ctx, collabID, world.viewerID, "ACCEPTED", commentsEpoch, commentsEpoch.Add(2*time.Minute))
	world.applyMember(t, ctx, collabID, pending, "PENDING", commentsEpoch, commentsEpoch.Add(time.Minute))

	viewerMux, ownerMux := world.mux, world.muxFor(t, owner)
	pendingMux, strangerMux := world.muxFor(t, pending), world.muxFor(t, stranger)

	// From the opening event: the accepted member and the owner read the text.
	feedItem := firstItem(t, getJSON(t, viewerMux, "/api/feed?limit=1"))
	for name, item := range map[string]map[string]any{
		"accepted member's feed item":      feedItem,
		"accepted member's post view":      getJSON(t, viewerMux, "/api/content/posts/"+first.String()),
		"accepted member's following feed": firstItem(t, getJSON(t, viewerMux, "/api/feed/following")),
		"owner's post view":                getJSON(t, ownerMux, "/api/content/posts/"+first.String()),
	} {
		if got := joinInstructionsOf(t, item); got != openingJoinInstructions {
			t.Fatalf("%s: joinInstructions = %v, want %q", name, got, openingJoinInstructions)
		}
	}
	assertNoJoinInstructions(t, pendingMux, strangerMux, first)

	// After the update event: the next request serves the new text.
	if !world.updateJoinInstructions(t, ctx, collabID, updatedJoinInstructions, commentsEpoch.Add(time.Hour)) {
		t.Fatal("update applied = false, want true for an event newer than the opening")
	}
	nextItem := firstItem(t, getJSON(t, viewerMux, "/api/feed?limit=1&lastSeenId="+feedItem["postId"].(string)))
	for name, item := range map[string]map[string]any{
		"accepted member's next feed item": nextItem,
		"accepted member's post view":      getJSON(t, viewerMux, "/api/content/posts/"+second.String()),
		"accepted member's following feed": firstItem(t, getJSON(t, viewerMux, "/api/feed/following")),
		"owner's post view":                getJSON(t, ownerMux, "/api/content/posts/"+second.String()),
	} {
		if got := joinInstructionsOf(t, item); got != updatedJoinInstructions {
			t.Fatalf("%s after the update: joinInstructions = %v, want %q", name, got, updatedJoinInstructions)
		}
	}
	assertNoJoinInstructions(t, pendingMux, strangerMux, second)

	// An older update is stale: nothing changes.
	if world.updateJoinInstructions(t, ctx, collabID, "Outdated text", commentsEpoch.Add(30*time.Minute)) {
		t.Fatal("update applied = true, want false for an event older than the stored text")
	}
	if got := joinInstructionsOf(t, getJSON(t, viewerMux, "/api/content/posts/"+first.String())); got != updatedJoinInstructions {
		t.Fatalf("after a stale update: joinInstructions = %v, want %q", got, updatedJoinInstructions)
	}

	// The member leaves: null from the next request on.
	world.applyMember(t, ctx, collabID, world.viewerID, "LEFT", commentsEpoch, commentsEpoch.Add(3*time.Hour))
	if got := joinInstructionsOf(t, getJSON(t, viewerMux, "/api/content/posts/"+first.String())); got != nil {
		t.Fatalf("former member's post view: joinInstructions = %v, want null", got)
	}

	// The ADMIN clears them: null even for the owner.
	world.updateJoinInstructions(t, ctx, collabID, "", commentsEpoch.Add(4*time.Hour))
	if got := joinInstructionsOf(t, getJSON(t, ownerMux, "/api/content/posts/"+first.String())); got != nil {
		t.Fatalf("owner's post view after clearing: joinInstructions = %v, want null", got)
	}
}

// assertNoJoinInstructions checks that a PENDING requester and a stranger read null on
// the post view and the following feed.
func assertNoJoinInstructions(t *testing.T, pendingMux, strangerMux *http.ServeMux, postID uuid.UUID) {
	t.Helper()

	for name, item := range map[string]map[string]any{
		"pending requester's post view":      getJSON(t, pendingMux, "/api/content/posts/"+postID.String()),
		"pending requester's following feed": firstItem(t, getJSON(t, pendingMux, "/api/feed/following")),
		"stranger's post view":               getJSON(t, strangerMux, "/api/content/posts/"+postID.String()),
		"stranger's following feed":          firstItem(t, getJSON(t, strangerMux, "/api/feed/following")),
	} {
		if got := joinInstructionsOf(t, item); got != nil {
			t.Fatalf("%s: joinInstructions = %v, want null", name, got)
		}
	}
}
