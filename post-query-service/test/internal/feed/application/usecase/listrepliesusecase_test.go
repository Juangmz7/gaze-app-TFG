package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// seedParent stores a top-level comment of postID by parent-author and returns its id.
func seedParent(store *fakeCommentStore, postID uuid.UUID) uuid.UUID {
	parentID := uuid.New()
	store.comments[parentID.String()] = usecase.ContentComment{CommentID: parentID.String(), PostID: postID.String(), AuthorID: "parent-author"}
	store.users["parent-author"] = usecase.FeedCreator{UserID: "parent-author", Username: "marta"}
	return parentID
}

func TestListRepliesUsecase_Execute_ReadsTheParentsRepliesAfterTheCursor(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	parentID := seedParent(store, postID)
	seedRows(store, postID, 3)
	store.blocked = []string{"blocked-1"}
	after := &usecase.RepliesCursor{CreatedAt: time.Date(2026, 3, 1, 9, 0, 0, 0, time.UTC), CommentID: uuid.NewString()}

	page, err := usecase.NewListReplies(store, discardLogger()).Execute(context.Background(),
		usecase.ListRepliesInput{UserID: uuid.New(), PostID: postID, CommentID: parentID, After: after, Limit: 2})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	query := store.repliesQuery
	if query.ParentID != parentID.String() || query.After != after || query.Limit != 3 || len(query.ExcludedAuthorIDs) != 1 || query.ExcludedAuthorIDs[0] != "blocked-1" {
		t.Fatalf("query = %+v, want limit+1 replies of the parent after the cursor, without blocked authors", query)
	}
	next, err := usecase.DecodeRepliesCursor(*page.NextCursor)
	if err != nil || next != usecase.RepliesCursorOf(store.rows[1]) {
		t.Fatalf("next cursor = %+v, %v, want the sort key of the last reply served", next, err)
	}
	if len(page.Items) != 2 || len(store.calls) != 6 {
		t.Fatalf("items = %d, calls = %v, want 2 replies and 6 queries", len(page.Items), store.calls)
	}
}

func TestListRepliesUsecase_Execute_ReturnsCommentNotFoundForAParentTheCallerMayNotSee(t *testing.T) {
	cases := map[string]func(store *fakeCommentStore, postID, parentID uuid.UUID){
		"parent missing": func(store *fakeCommentStore, _, parentID uuid.UUID) { delete(store.comments, parentID.String()) },
		"parent of another post": func(store *fakeCommentStore, _, parentID uuid.UUID) {
			parent := store.comments[parentID.String()]
			parent.PostID = uuid.NewString()
			store.comments[parentID.String()] = parent
		},
		"parent author blocked":       func(store *fakeCommentStore, _, _ uuid.UUID) { store.blocked = []string{"parent-author"} },
		"parent author not projected": func(store *fakeCommentStore, _, _ uuid.UUID) { delete(store.users, "parent-author") },
	}

	for name, hide := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeCommentStore()
			postID := seedCommentedPost(store)
			parentID := seedParent(store, postID)
			hide(store, postID, parentID)

			_, err := usecase.NewListReplies(store, discardLogger()).Execute(context.Background(), usecase.ListRepliesInput{UserID: uuid.New(), PostID: postID, CommentID: parentID, Limit: 20})

			if !errors.Is(err, usecase.ErrCommentNotFound) {
				t.Fatalf("Execute() error = %v, want ErrCommentNotFound", err)
			}
		})
	}
}

func TestListRepliesUsecase_Execute_ReturnsPostNotFoundBeforeLookingAtTheParent(t *testing.T) {
	store := newFakeCommentStore()
	postID := seedCommentedPost(store)
	parentID := seedParent(store, postID)
	store.blocked = []string{"creator-1"}

	_, err := usecase.NewListReplies(store, discardLogger()).Execute(context.Background(), usecase.ListRepliesInput{UserID: uuid.New(), PostID: postID, CommentID: parentID, Limit: 20})

	if !errors.Is(err, usecase.ErrCommentsPostNotFound) || store.calls["comment"] != 0 {
		t.Fatalf("Execute() error = %v, calls = %v, want post not found and no parent lookup", err, store.calls)
	}
}

func TestListRepliesUsecase_Execute_RejectsAMissingCommentID(t *testing.T) {
	store := newFakeCommentStore()

	_, err := usecase.NewListReplies(store, discardLogger()).Execute(context.Background(), usecase.ListRepliesInput{UserID: uuid.New(), PostID: uuid.New(), Limit: 20})

	if err == nil || len(store.calls) != 0 {
		t.Fatalf("Execute() error = %v, calls = %v, want an error before any query", err, store.calls)
	}
}
