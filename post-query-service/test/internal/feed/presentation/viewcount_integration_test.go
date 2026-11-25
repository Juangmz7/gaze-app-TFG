package presentation_test

import (
	"context"
	"encoding/json"
	"maps"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"

	viewusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/view/application/usecase"
)

// consumeView runs a PostViewedEvent's projection through the real usecase and
// repositories, the way PostViewedHandler does inside its transaction (tested on its own
// against a replica set): the history row, then +1 on the post.
func (a *feedApp) consumeView(t *testing.T, ctx context.Context, userID, postID uuid.UUID, viewedAt time.Time) {
	t.Helper()

	input := viewusecase.RecordPostViewInput{UserID: userID, PostID: postID, ViewedAt: viewedAt, CorrelationID: uuid.New()}
	if err := viewusecase.NewRecordPostView(a.history, a.posts, 5, discardLogger()).Execute(ctx, input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
}

// viewCountsOf GETs target and returns each item's viewCount by postId, failing unless
// every item carries viewCount as a JSON number next to likeCount.
func viewCountsOf(t *testing.T, mux *http.ServeMux, target string) map[string]int64 {
	t.Helper()

	recorder := serve(mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var page struct {
		Items []map[string]any `json:"items"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &page); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}

	counts := make(map[string]int64, len(page.Items))
	for _, item := range page.Items {
		if _, ok := item["likeCount"].(float64); !ok {
			t.Fatalf("GET %s item %v has no numeric likeCount", target, item)
		}
		viewCount, ok := item["viewCount"].(float64)
		if !ok {
			t.Fatalf("GET %s item %v: viewCount = %#v, want a number (never null or omitted)", target, item["postId"], item["viewCount"])
		}
		counts[item["postId"].(string)] = int64(viewCount)
	}
	return counts
}

func TestGridLists_ReturnEachPostsViewCountAfterViewsAreConsumed(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	other := app.registerUser(t, ctx, "bruno")

	viewed := app.insertPostBy(t, ctx, app.creator, commentsEpoch)
	unviewed := app.insertPostBy(t, ctx, app.creator, commentsEpoch.Add(-time.Hour))
	// A post projected before task 63: its stats have no view_count at all.
	legacy := app.insertPostBy(t, ctx, app.creator, commentsEpoch.Add(-2*time.Hour))
	if _, err := app.db.Collection("posts").UpdateOne(ctx, bson.D{{Key: "post_id", Value: legacy.String()}}, bson.D{{Key: "$unset", Value: bson.D{{Key: "stats.view_count", Value: ""}}}}); err != nil {
		t.Fatalf("UpdateOne() error = %v", err)
	}

	// Three views of one post (two by the caller, one by someone else); the caller likes
	// the posts and the other user reposts them, so they appear on every list.
	app.consumeView(t, ctx, app.viewerID, viewed, commentsEpoch.Add(time.Minute))
	app.consumeView(t, ctx, app.viewerID, viewed, commentsEpoch.Add(2*time.Minute))
	app.consumeView(t, ctx, other, viewed, commentsEpoch.Add(3*time.Minute))
	for _, postID := range []uuid.UUID{viewed, unviewed, legacy} {
		app.like(t, ctx, app.viewerID, postID, commentsEpoch.Add(time.Hour))
		app.share(t, ctx, other, postID, commentsEpoch.Add(time.Hour))
	}
	// The history tab only lists what the caller viewed, so the other two are viewed by
	// the caller straight in view_history, without counting.
	app.view(t, ctx, app.viewerID, unviewed, commentsEpoch.Add(time.Minute))
	app.view(t, ctx, app.viewerID, legacy, commentsEpoch.Add(time.Minute))

	want := map[string]int64{viewed.String(): 3, unviewed.String(): 0, legacy.String(): 0}
	targets := []string{
		gridTarget(app.creator, "posts", 20, nil),
		gridTarget(other, "reposts", 20, nil),
		ownTarget("liked", 20, nil),
		ownTarget("history", 20, nil),
	}
	for _, target := range targets {
		if got := viewCountsOf(t, app.mux, target); !maps.Equal(got, want) {
			t.Fatalf("GET %s viewCounts = %v, want %v", target, got, want)
		}
	}

	// The first view of the legacy post creates its counter: it now shows 1.
	app.consumeView(t, ctx, other, legacy, commentsEpoch.Add(4*time.Minute))
	want[legacy.String()] = 1
	for _, target := range targets {
		if got := viewCountsOf(t, app.mux, target); !maps.Equal(got, want) {
			t.Fatalf("after the legacy post's first view, GET %s viewCounts = %v, want %v", target, got, want)
		}
	}
}
