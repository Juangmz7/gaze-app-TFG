package presentation_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	blockusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/application/usecase"
	blockmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/block/infrastructure/mongo"
	collabusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	followusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/application/usecase"
	followmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/follow/infrastructure/mongo"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

func (a *feedApp) getPost(t *testing.T, postID string) (int, map[string]any) {
	t.Helper()

	recorder := serve(a.mux, http.MethodGet, "/api/content/posts/"+postID, "", nil)
	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("GET /api/content/posts/%s body is not JSON (status %d): %v", postID, recorder.Code, err)
	}
	return recorder.Code, body
}

func sampleMedia() []postusecase.PostMedia {
	duration := int64(15000)
	thumbnail := "https://media.test/thumb.jpg"
	return []postusecase.PostMedia{
		{ID: uuid.New(), URL: "https://media.test/b.mp4", ThumbnailURL: &thumbnail, MediaType: "VIDEO", Duration: &duration, TaggedUsers: []string{"ana"}, Order: 2},
		{ID: uuid.New(), URL: "https://media.test/a.jpg", MediaType: "IMAGE", Order: 1},
	}
}

func TestGetPost_ReturnsThePostViewWithTheCallersViewerFlags(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())

	if _, err := app.posts.AdjustLikeCount(ctx, postID, 2); err != nil {
		t.Fatalf("AdjustLikeCount() error = %v", err)
	}
	if _, err := app.db.Collection("post_likes").InsertOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: app.viewerID.String()}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	// Someone else's share must not show up as the caller's.
	if _, err := app.db.Collection("post_shares").InsertOne(ctx, bson.D{{Key: "post_id", Value: postID.String()}, {Key: "user_id", Value: uuid.NewString()}}); err != nil {
		t.Fatalf("InsertOne() error = %v", err)
	}
	if err := followmongo.NewRepository(app.db).Insert(ctx, followusecase.CreateFollowInput{FollowerUserID: app.viewerID, FollowedUserID: app.creator, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	collabID := uuid.New()
	if err := collabmongo.NewRepository(app.db).Insert(ctx, collabusecase.RecordCollabOpenedInput{CollabID: collabID, PostID: postID, OwnerUserID: app.creator, Title: "Harbour", Status: "OPEN", CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	status, view := app.getPost(t, postID.String())

	if status != http.StatusOK {
		t.Fatalf("status = %d, want 200: %v", status, view)
	}
	if _, found := view["position"]; found {
		t.Fatalf("post view = %v, want no position", view)
	}
	if view["postId"] != postID.String() || view["description"] != "Harbour at dusk" || view["createdAt"] != "2026-03-01T10:00:00Z" {
		t.Fatalf("post view = %v", view)
	}
	creator := view["creator"].(map[string]any)
	if creator["userId"] != app.creator.String() || creator["username"] != "ana_rivera" || creator["pictureUrl"] != nil {
		t.Fatalf("creator = %v", creator)
	}
	if view["postType"] != "COLLAB" || view["collabId"] != collabID.String() {
		t.Fatalf("postType = %v, collabId = %v", view["postType"], view["collabId"])
	}
	collab := view["collab"].(map[string]any)
	if collab["title"] != "Harbour" || collab["status"] != "OPEN" || collab["pendingRequestCount"] != nil {
		t.Fatalf("collab = %v", collab)
	}
	media := view["media"].([]any)
	if len(media) != 2 || media[0].(map[string]any)["mediaType"] != "IMAGE" || media[1].(map[string]any)["durationMs"] != float64(15000) {
		t.Fatalf("media = %v, want both elements sorted by order", media)
	}
	if view["coverThumbnailUrl"] != "https://media.test/a.jpg" || view["durationMs"] != float64(15000) {
		t.Fatalf("cover = %v, duration = %v", view["coverThumbnailUrl"], view["durationMs"])
	}
	if stats := view["stats"].(map[string]any); stats["likeCount"] != float64(2) || stats["shareCount"] != float64(0) {
		t.Fatalf("stats = %v", stats)
	}
	viewer := view["viewer"].(map[string]any)
	if viewer["liked"] != true || viewer["shared"] != false || viewer["followingCreator"] != true || viewer["collabRequestStatus"] != nil {
		t.Fatalf("viewer = %v, want liked and following, not shared", viewer)
	}

	// Reading a post is not a feed claim: no unit is created or served.
	units, err := app.db.Collection("feed_units").CountDocuments(ctx, bson.D{})
	if err != nil {
		t.Fatalf("CountDocuments() error = %v", err)
	}
	if units != 0 {
		t.Fatalf("feed units = %d, want 0", units)
	}
}

func TestGetPost_AnswersTheSame404ForAMissingDeletedOrBlockedPost(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	blocks := blockmongo.NewRepository(app.db)
	if err := blocks.EnsureIndexes(ctx); err != nil {
		t.Fatalf("EnsureIndexes() error = %v", err)
	}

	cases := map[string]func(t *testing.T) uuid.UUID{
		"never projected": func(*testing.T) uuid.UUID { return uuid.New() },
		"deleted": func(t *testing.T) uuid.UUID {
			postID := app.insertPost(t, ctx, sampleMedia())
			if err := app.posts.Delete(ctx, postID); err != nil {
				t.Fatalf("Delete() error = %v", err)
			}
			return postID
		},
		"its creator blocked the caller": func(t *testing.T) uuid.UUID {
			postID := app.insertPost(t, ctx, sampleMedia())
			if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.creator, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			t.Cleanup(func() { _ = blocks.Delete(context.Background(), app.creator, app.viewerID) })
			return postID
		},
		"the caller blocked its creator": func(t *testing.T) uuid.UUID {
			postID := app.insertPost(t, ctx, sampleMedia())
			if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.viewerID, BlockedUserID: app.creator, CreatedAt: time.Now().UTC()}); err != nil {
				t.Fatalf("Insert() error = %v", err)
			}
			t.Cleanup(func() { _ = blocks.Delete(context.Background(), app.viewerID, app.creator) })
			return postID
		},
	}

	for name, hide := range cases {
		t.Run(name, func(t *testing.T) {
			postID := hide(t)

			recorder := serve(app.mux, http.MethodGet, "/api/content/posts/"+postID.String(), "", nil)

			if recorder.Code != http.StatusNotFound {
				t.Fatalf("status = %d, want 404: %s", recorder.Code, recorder.Body)
			}
			var body common.ApiErrorResponse
			if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
				t.Fatalf("Unmarshal() error = %v", err)
			}
			if body.Code != "post_not_found" || body.Message != "Post not found" || len(body.Details) != 0 {
				t.Fatalf("body = %+v, want the one post_not_found answer", body)
			}
		})
	}
}

func TestGetPost_ServesThePostAgainOnceTheBlockIsLifted(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	postID := app.insertPost(t, ctx, sampleMedia())
	blocks := blockmongo.NewRepository(app.db)

	if err := blocks.Insert(ctx, blockusecase.CreateBlockInput{BlockerUserID: app.creator, BlockedUserID: app.viewerID, CreatedAt: time.Now().UTC()}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	if status, _ := app.getPost(t, postID.String()); status != http.StatusNotFound {
		t.Fatalf("status while blocked = %d, want 404", status)
	}

	if err := blocks.Delete(ctx, app.creator, app.viewerID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}
	if status, _ := app.getPost(t, postID.String()); status != http.StatusOK {
		t.Fatalf("status after the block = %d, want 200", status)
	}
}

func TestGetPost_RejectsAMalformedIDWith400(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)

	status, body := app.getPost(t, "not-a-post-id")

	if status != http.StatusBadRequest || body["code"] != "invalid_post_id" {
		t.Fatalf("status = %d, body = %v, want 400 invalid_post_id", status, body)
	}
}
