package bootstrap_test

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"

	feedusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	feedmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

const (
	brokerOpeningJoinInstructions = "Welcome! Chat: https://chat.example.test/invite/harbour"
	brokerUpdatedJoinInstructions = "Repo: https://git.example.test/harbour"
)

// collabOpenedWithInstructionsJSON is the Java CollabOpenedEvent of a collab opened on
// an existing post, with the joinInstructions field task 61 adds.
func collabOpenedWithInstructionsJSON(collabID, postID, ownerID, instructions string) string {
	return fmt.Sprintf(`{%s,"collabId":"%s","title":"Harbour series","createdBy":"%s","collabStatus":"OPEN","collabCreatedAt":"%s","joinInstructions":"%s","creatorMemberStatus":"ACCEPTED","creatorRole":"ADMIN","creatorMemberCreatedAt":"%s","postId":"%s","userId":"%s","postCollabId":"%s","postType":"BASIC","description":"Sunset","postTags":[],"media":[],"postCreatedAt":"%s","postUpdatedAt":"%s","withPostCreated":false}`,
		envelope(), collabID, ownerID, testInstant, instructions, testInstant, postID, ownerID, collabID, testInstant, testInstant)
}

// collabJoinInstructionsUpdatedJSON is the Java CollabJoinInstructionsUpdatedEvent;
// instructions is a JSON value (a quoted string or null).
func collabJoinInstructionsUpdatedJSON(eventID, collabID, instructions, occurredAt string) string {
	return fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"%s","collabId":"%s","joinInstructions":%s}`,
		eventID, uuid.NewString(), occurredAt, collabID, instructions)
}

// collabRequestAcceptedJSON and collabRequestCreatedJSON are the Java membership events
// of task 52.
func collabRequestAcceptedJSON(collabID, userID, ownerID string) string {
	return fmt.Sprintf(`{%s,"collabId":"%s","userId":"%s","acceptedBy":"%s","collabMemberStatus":"ACCEPTED","role":"MEMBER","memberCreatedAt":"%s"}`,
		envelope(), collabID, userID, ownerID, testInstant)
}

func collabRequestCreatedJSON(collabID, userID string) string {
	return fmt.Sprintf(`{%s,"collabId":"%s","userId":"%s","status":"PENDING","role":"MEMBER","createdAt":"%s"}`,
		envelope(), collabID, userID, testInstant)
}

// Acceptance (through a real broker): an ACCEPTED member's post reads joinInstructions
// from CollabOpenedEvent and, after CollabJoinInstructionsUpdatedEvent is consumed, the
// new text; a PENDING requester reads null. A duplicate and an older update change
// nothing, and a malformed update is dead-lettered without retries.
func TestConsumers_ProjectRealJoinInstructionsEventsServedOnlyToAcceptedMembers(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	app := startJavaEventsApp(t, ctx)

	ownerID, memberID, pendingID := uuid.NewString(), uuid.NewString(), uuid.NewString()
	postID, collabID := uuid.NewString(), uuid.NewString()
	app.publish(t, ctx, topology.ExchangeUserEvents, topology.RKUserRegistered, fmt.Sprintf(
		`{%s,"userId":"%s","username":"ana_rivera","email":"ana@example.test","bio":{"description":"Photographer","socialMedia":{}}}`, envelope(), ownerID))
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCreated, postCreatedJSON(postID, ownerID))
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabOpenedExistingPost, collabOpenedWithInstructionsJSON(collabID, postID, ownerID, brokerOpeningJoinInstructions))
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabRequestAccepted, collabRequestAcceptedJSON(collabID, memberID, ownerID))
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabRequestCreated, collabRequestCreatedJSON(collabID, pendingID))
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)
	var owner struct {
		UserID string `bson:"user_id"`
	}
	app.waitForDocument(t, ctx, "users", bson.D{{Key: "user_id", Value: ownerID}}, &owner)

	getPost := feedusecase.NewGetPost(feedmongo.NewHydrationRepository(app.db), slog.New(slog.NewTextHandler(io.Discard, nil)))
	instructionsFor := func(t *testing.T, viewerID string) *string {
		t.Helper()
		card, err := getPost.Execute(ctx, feedusecase.GetPostInput{UserID: uuid.MustParse(viewerID), PostID: uuid.MustParse(postID)})
		if err != nil {
			t.Fatalf("GetPost() error = %v", err)
		}
		if card.CollabID == nil || *card.CollabID != collabID {
			t.Fatalf("card collab = %v, want %s", card.CollabID, collabID)
		}
		return card.CollabJoinInstructions
	}
	expect := func(t *testing.T, when string, want string) {
		t.Helper()
		for _, viewer := range []struct{ name, id string }{{"accepted member", memberID}, {"owner", ownerID}} {
			if got := instructionsFor(t, viewer.id); got == nil || *got != want {
				t.Fatalf("%s's joinInstructions %s = %v, want %q", viewer.name, when, got, want)
			}
		}
		if got := instructionsFor(t, pendingID); got != nil {
			t.Fatalf("pending requester's joinInstructions %s = %q, want null", when, *got)
		}
	}

	expect(t, "after CollabOpenedEvent", brokerOpeningJoinInstructions)

	updateID := uuid.NewString()
	update := collabJoinInstructionsUpdatedJSON(updateID, collabID, `"`+brokerUpdatedJoinInstructions+`"`, "2026-03-01T11:00:00.5Z")
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabJoinInstructionsUpdated, update)
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabJoinInstructionsUpdated, update)
	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabJoinInstructionsUpdated,
		collabJoinInstructionsUpdatedJSON(uuid.NewString(), collabID, `null`, "2026-03-01T10:00:00Z"))
	app.waitUntilDrained(t, ctx, topology.QueuePost, 0)

	expect(t, "after the update, a duplicate and an older clear", brokerUpdatedJoinInstructions)
	var stored struct {
		Version int64 `bson:"version"`
	}
	app.waitForDocument(t, ctx, "post_collabs", bson.D{{Key: "collab_id", Value: collabID}}, &stored)
	if stored.Version != 2 {
		t.Fatalf("collab version = %d, want 2: one write on top of the opening", stored.Version)
	}
	count, err := app.db.Collection("processed_events").CountDocuments(ctx, bson.D{{Key: "event_id", Value: uuid.MustParse(updateID)}})
	if err != nil || count != 1 {
		t.Fatalf("processed_events for the update = %d (%v), want 1", count, err)
	}

	app.publish(t, ctx, topology.ExchangePostEvents, topology.RKPostCollabJoinInstructionsUpdated,
		fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T12:00:00Z","collab_id":"%s","joinInstructions":null}`, uuid.NewString(), uuid.NewString(), collabID))
	app.waitUntilDrained(t, ctx, topology.QueuePost, 1)
	expect(t, "after a malformed update", brokerUpdatedJoinInstructions)
}
