package usecase_test

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeOwnListStore adds the own tabs' rows to fakeGridStore: likes and views per user,
// each list already in its order, served after the cursor's row like the grids'.
type fakeOwnListStore struct {
	*fakeGridStore
	likes    map[string][]usecase.PostRef
	views    map[string][]usecase.PostRef
	rowsErr  error
	rowUsers []string
}

func newFakeOwnListStore() *fakeOwnListStore {
	return &fakeOwnListStore{fakeGridStore: newFakeGridStore(), likes: map[string][]usecase.PostRef{}, views: map[string][]usecase.PostRef{}}
}

func (f *fakeOwnListStore) page(rows []usecase.PostRef, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	f.calls["page"]++
	f.queries = append(f.queries, query)
	f.rowUsers = append(f.rowUsers, query.UserID)
	if f.rowsErr != nil {
		return nil, f.rowsErr
	}
	start := 0
	if query.After != nil {
		start = slices.IndexFunc(rows, func(row usecase.PostRef) bool { return row.PostID == query.After.PostID }) + 1
	}
	return rows[start:min(len(rows), start+query.Limit)], nil
}

func (f *fakeOwnListStore) FindUserLikes(_ context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	return f.page(f.likes[query.UserID], query)
}

func (f *fakeOwnListStore) FindUserViews(_ context.Context, query usecase.UserGridQuery) ([]usecase.PostRef, error) {
	return f.page(f.views[query.UserID], query)
}

// ownListUsecase runs one own tab against the store.
type ownListUsecase func(store *fakeOwnListStore) func(context.Context, usecase.OwnListInput) (usecase.TimedGridPage, error)

// ownLists are both own tabs, with the rows each reads.
var ownLists = map[string]struct {
	run  ownListUsecase
	rows func(store *fakeOwnListStore) map[string][]usecase.PostRef
}{
	"liked": {
		run: func(store *fakeOwnListStore) func(context.Context, usecase.OwnListInput) (usecase.TimedGridPage, error) {
			return usecase.NewListMyLiked(store, discardLogger()).Execute
		},
		rows: func(store *fakeOwnListStore) map[string][]usecase.PostRef { return store.likes },
	},
	"history": {
		run: func(store *fakeOwnListStore) func(context.Context, usecase.OwnListInput) (usecase.TimedGridPage, error) {
			return usecase.NewListMyHistory(store, discardLogger()).Execute
		},
		rows: func(store *fakeOwnListStore) map[string][]usecase.PostRef { return store.views },
	},
}

// seedOwnRows gives userID count visible posts by a projected creator, the row of the
// one at index i at gridEpoch minus i minutes (newest first), and returns them.
func seedOwnRows(store *fakeOwnListStore, rows map[string][]usecase.PostRef, userID string, count int) []usecase.FeedPost {
	creator := uuid.NewString()
	store.users[creator] = usecase.FeedCreator{UserID: creator, Username: "ana"}
	posts := make([]usecase.FeedPost, 0, count)
	for index := range count {
		// The post's own date differs from the row's: items must carry the row's time.
		post := gridPostOf(creator, count-index)
		store.posts[post.PostID] = post
		rows[userID] = append(rows[userID], usecase.PostRef{PostID: post.PostID, At: gridEpoch.Add(-time.Duration(index) * time.Minute)})
		posts = append(posts, post)
	}
	return posts
}

func TestOwnListUsecases_Execute_PageTheCallersRowsNewestFirstWithTheRowTime(t *testing.T) {
	for name, list := range ownLists {
		t.Run(name, func(t *testing.T) {
			store := newFakeOwnListStore()
			caller := uuid.New()
			posts := seedOwnRows(store, list.rows(store), caller.String(), 5)
			execute := list.run(store)

			var served []string
			var times []time.Time
			input := usecase.OwnListInput{UserID: caller, Limit: 2, CorrelationID: "corr-1"}
			var sizes []int
			for range 5 {
				page, err := execute(context.Background(), input)
				if err != nil {
					t.Fatalf("Execute() error = %v", err)
				}
				sizes = append(sizes, len(page.Items))
				for _, item := range page.Items {
					served = append(served, item.PostID)
					times = append(times, item.At)
				}
				if page.NextCursor == nil {
					break
				}
				cursor, err := usecase.DecodeGridCursor(*page.NextCursor)
				if err != nil {
					t.Fatalf("DecodeGridCursor() error = %v", err)
				}
				input.After = &cursor
			}

			if !slices.Equal(sizes, []int{2, 2, 1}) {
				t.Fatalf("page sizes = %v, want [2 2 1] with a null cursor on the last", sizes)
			}
			for index, post := range posts {
				if served[index] != post.PostID || !times[index].Equal(gridEpoch.Add(-time.Duration(index)*time.Minute)) {
					t.Fatalf("item %d = %s at %v, want %s at its row's time", index, served[index], times[index], post.PostID)
				}
			}
		})
	}
}

func TestOwnListUsecases_Execute_ReadOnlyTheCallersList(t *testing.T) {
	for name, list := range ownLists {
		t.Run(name, func(t *testing.T) {
			store := newFakeOwnListStore()
			ana, bruno := uuid.New(), uuid.New()
			anas := seedOwnRows(store, list.rows(store), ana.String(), 2)
			brunos := seedOwnRows(store, list.rows(store), bruno.String(), 3)
			execute := list.run(store)

			for caller, want := range map[uuid.UUID][]usecase.FeedPost{ana: anas, bruno: brunos} {
				page, err := execute(context.Background(), usecase.OwnListInput{UserID: caller, Limit: 20})
				if err != nil {
					t.Fatalf("Execute() error = %v", err)
				}
				var got []string
				for _, item := range page.Items {
					got = append(got, item.PostID)
				}
				var wantIDs []string
				for _, post := range want {
					wantIDs = append(wantIDs, post.PostID)
				}
				if !slices.Equal(got, wantIDs) {
					t.Fatalf("%s served %v, want only their own %v", caller, got, wantIDs)
				}
			}
			for _, user := range store.rowUsers {
				if user != ana.String() && user != bruno.String() {
					t.Fatalf("rows read for %s, want only the callers'", user)
				}
			}
		})
	}
}

func TestOwnListUsecases_Execute_SkipGoneAndHiddenPostsAndStillFillThePage(t *testing.T) {
	for name, list := range ownLists {
		t.Run(name, func(t *testing.T) {
			store := newFakeOwnListStore()
			caller := uuid.New()
			rows := list.rows(store)
			posts := seedOwnRows(store, rows, caller.String(), 6)
			blocker, unprojected := uuid.NewString(), uuid.NewString()
			store.users[blocker] = usecase.FeedCreator{UserID: blocker}
			store.blocked = []string{blocker}
			delete(store.posts, posts[0].PostID)
			hidden := store.posts[posts[1].PostID]
			hidden.CreatorID = blocker
			store.posts[hidden.PostID] = hidden
			orphan := store.posts[posts[2].PostID]
			orphan.CreatorID = unprojected
			store.posts[orphan.PostID] = orphan

			page, err := list.run(store)(context.Background(), usecase.OwnListInput{UserID: caller, Limit: 2})
			if err != nil {
				t.Fatalf("Execute() error = %v", err)
			}

			if got := []string{page.Items[0].PostID, page.Items[1].PostID}; len(page.Items) != 2 || !slices.Equal(got, []string{posts[3].PostID, posts[4].PostID}) {
				t.Fatalf("items = %+v, want the first two visible posts", page.Items)
			}
			if page.NextCursor == nil {
				t.Fatal("next cursor = nil, want one: a row is left")
			}
			if store.calls["blocks"] != 1 || store.calls["users"] != store.calls["page"] || store.calls["posts"] != store.calls["page"] || store.calls["collabs"] != 1 {
				t.Fatalf("calls = %v, want one block read, one posts and one users $in per row read, one collab read", store.calls)
			}
			for _, lookup := range store.postLookups {
				if len(lookup) > 3 {
					t.Fatalf("post lookups = %v: a read asks for the missing items + 1 at most", store.postLookups)
				}
			}
		})
	}
}

func TestOwnListUsecases_Execute_RejectAnInvalidInputWithoutReading(t *testing.T) {
	for name, list := range ownLists {
		for invalid, input := range map[string]usecase.OwnListInput{
			"no caller":     {Limit: 20},
			"zero limit":    {UserID: uuid.New()},
			"limit too big": {UserID: uuid.New(), Limit: usecase.KeysetMaxLimit + 1},
		} {
			t.Run(name+" "+invalid, func(t *testing.T) {
				store := newFakeOwnListStore()
				if _, err := list.run(store)(context.Background(), input); err == nil {
					t.Fatal("Execute() error = nil, want a validation error")
				}
				if len(store.calls) != 0 {
					t.Fatalf("calls = %v, want none", store.calls)
				}
			})
		}
	}
}

func TestOwnListUsecases_Execute_PropagateAReadFailure(t *testing.T) {
	for name, list := range ownLists {
		t.Run(name, func(t *testing.T) {
			store := newFakeOwnListStore()
			store.rowsErr = errors.New("mongo down")

			if _, err := list.run(store)(context.Background(), usecase.OwnListInput{UserID: uuid.New(), Limit: 20}); !errors.Is(err, store.rowsErr) {
				t.Fatalf("Execute() error = %v, want it to wrap the read failure", err)
			}
		})
	}
}
