package usecase_test

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeFollowingStore is the read model of the following feed: follows per follower
// (most recent first), blocks, users, posts and the viewer state, with every query
// recorded.
type fakeFollowingStore struct {
	follows  map[string][]string
	blocks   map[string][]string
	users    map[string]usecase.FeedCreator
	posts    []usecase.FeedPost
	liked    map[string]bool
	calls    map[string]int
	queries  []usecase.FollowingPostsQuery
	limits   []int
	checked  [][]string
	failWith map[string]error
}

func newFakeFollowingStore() *fakeFollowingStore {
	return &fakeFollowingStore{
		follows:  map[string][]string{},
		blocks:   map[string][]string{},
		users:    map[string]usecase.FeedCreator{},
		liked:    map[string]bool{},
		calls:    map[string]int{},
		failWith: map[string]error{},
	}
}

func (f *fakeFollowingStore) track(name string) error {
	f.calls[name]++
	return f.failWith[name]
}

func (f *fakeFollowingStore) FindRecentFollowedUserIDs(_ context.Context, viewerID string, limit int) ([]string, error) {
	f.limits = append(f.limits, limit)
	if err := f.track("follows"); err != nil {
		return nil, err
	}
	followed := f.follows[viewerID]
	return slices.Clone(followed[:min(limit, len(followed))]), nil
}

func (f *fakeFollowingStore) FindAllBlockRelatedUserIDs(_ context.Context, viewerID string) ([]string, error) {
	if err := f.track("blocks"); err != nil {
		return nil, err
	}
	return f.blocks[viewerID], nil
}

func (f *fakeFollowingStore) FindProjectedUserIDs(_ context.Context, userIDs []string) ([]string, error) {
	f.checked = append(f.checked, slices.Clone(userIDs))
	if err := f.track("projected"); err != nil {
		return nil, err
	}
	var projected []string
	for _, userID := range userIDs {
		if _, found := f.users[userID]; found {
			projected = append(projected, userID)
		}
	}
	return projected, nil
}

// FindFollowingPosts serves the posts of query.UserIDs strictly after the cursor,
// (created_at, post_id) descending, as the repository does.
func (f *fakeFollowingStore) FindFollowingPosts(_ context.Context, query usecase.FollowingPostsQuery) ([]usecase.FeedPost, error) {
	f.queries = append(f.queries, usecase.FollowingPostsQuery{UserIDs: slices.Clone(query.UserIDs), After: query.After, Limit: query.Limit})
	if err := f.track("page"); err != nil {
		return nil, err
	}
	var rows []usecase.FeedPost
	for _, post := range f.posts {
		if !slices.Contains(query.UserIDs, post.CreatorID) {
			continue
		}
		if after := query.After; after != nil {
			if c := post.CreatedAt.Compare(after.CreatedAt); c > 0 || (c == 0 && post.PostID >= after.PostID) {
				continue
			}
		}
		rows = append(rows, post)
	}
	slices.SortFunc(rows, newestPostFirst)
	return rows[:min(query.Limit, len(rows))], nil
}

func (f *fakeFollowingStore) FindCreators(_ context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	if err := f.track("creators"); err != nil {
		return nil, err
	}
	var creators []usecase.FeedCreator
	for _, userID := range userIDs {
		if creator, found := f.users[userID]; found {
			creators = append(creators, creator)
		}
	}
	return creators, nil
}

func (f *fakeFollowingStore) FindCollabs(context.Context, []string, []string) ([]usecase.FeedCollab, error) {
	return nil, f.track("collabs")
}

func (f *fakeFollowingStore) FindLikedPostIDs(_ context.Context, _ string, postIDs []string) ([]string, error) {
	var liked []string
	for _, postID := range postIDs {
		if f.liked[postID] {
			liked = append(liked, postID)
		}
	}
	return liked, f.track("liked")
}

func (f *fakeFollowingStore) FindSharedPostIDs(context.Context, string, []string) ([]string, error) {
	return nil, f.track("shared")
}

func (f *fakeFollowingStore) FindFollowedUserIDs(_ context.Context, viewerID string, userIDs []string) ([]string, error) {
	var followed []string
	for _, userID := range userIDs {
		if slices.Contains(f.follows[viewerID], userID) {
			followed = append(followed, userID)
		}
	}
	return followed, f.track("followed")
}

func (f *fakeFollowingStore) FindBlockRelatedUserIDs(_ context.Context, viewerID string, userIDs []string) ([]string, error) {
	var related []string
	for _, userID := range userIDs {
		if slices.Contains(f.blocks[viewerID], userID) {
			related = append(related, userID)
		}
	}
	return related, f.track("pageBlocks")
}

func newestPostFirst(a, b usecase.FeedPost) int {
	return cmp.Or(b.CreatedAt.Compare(a.CreatedAt), strings.Compare(b.PostID, a.PostID))
}

// projectUser stores a user and returns its id.
func (f *fakeFollowingStore) projectUser(username string) string {
	userID := uuid.NewString()
	f.users[userID] = usecase.FeedCreator{UserID: userID, Username: username}
	return userID
}

// postBy stores a post of creatorID created minutesAgo before gridEpoch.
func (f *fakeFollowingStore) postBy(creatorID string, minutesAgo int) usecase.FeedPost {
	post := gridPostOf(creatorID, minutesAgo)
	f.posts = append(f.posts, post)
	return post
}

func followingInput(caller uuid.UUID, limit int) usecase.FollowingFeedInput {
	return usecase.FollowingFeedInput{UserID: caller, Limit: limit, CorrelationID: "corr-1"}
}

func cardIDs(cards []usecase.FeedCard) []string {
	ids := make([]string, 0, len(cards))
	for _, card := range cards {
		ids = append(ids, card.PostID)
	}
	return ids
}

func TestFollowingFeedUsecase_Execute_BuildsTheQueryFromFollowedIDsAndAppliesTheCap(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	// Most recently followed first: only the first three are within the cap.
	followed := []string{store.projectUser("a"), store.projectUser("b"), store.projectUser("c"), store.projectUser("d"), store.projectUser("e")}
	store.follows[caller.String()] = followed
	logger, logs := bufferLogger()

	_, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 3}, logger).Execute(context.Background(), followingInput(caller, 20))
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if !slices.Equal(store.limits, []int{4}) {
		t.Fatalf("follows read with limits %v, want one read of the cap plus one", store.limits)
	}
	if len(store.queries) != 1 {
		t.Fatalf("page queries = %d, want 1", len(store.queries))
	}
	query := store.queries[0]
	if !slices.Equal(query.UserIDs, followed[:3]) || query.Limit != 21 || query.After != nil {
		t.Fatalf("query = %+v, want the 3 most recent follows, limit 21 and no cursor", query)
	}
	if !strings.Contains(logs.String(), "using the most recent follows only") || !strings.Contains(logs.String(), "maxFollows=3") ||
		!strings.Contains(logs.String(), "correlation_id=corr-1") {
		t.Fatalf("logs = %q, want the cap logged with the correlation id", logs.String())
	}
}

func TestFollowingFeedUsecase_Execute_DoesNotLogTheCapWhenTheFollowsFitInIt(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	store.follows[caller.String()] = []string{store.projectUser("a"), store.projectUser("b"), store.projectUser("c")}
	logger, logs := bufferLogger()

	if _, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 3}, logger).Execute(context.Background(), followingInput(caller, 20)); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(store.queries[0].UserIDs) != 3 || strings.Contains(logs.String(), "most recent follows only") {
		t.Fatalf("query ids = %v, logs = %q, want every follow and no cap log", store.queries[0].UserIDs, logs.String())
	}
}

func TestFollowingFeedUsecase_Execute_ServesOnlyFollowedUsersPostsNewestFirst(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	ana, bruno, stranger := store.projectUser("ana"), store.projectUser("bruno"), store.projectUser("stranger")
	store.follows[caller.String()] = []string{ana, bruno}
	anaOld, brunoNew, anaNew := store.postBy(ana, 30), store.postBy(bruno, 5), store.postBy(ana, 10)
	store.postBy(stranger, 1)
	store.liked[anaNew.PostID] = true

	page, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger()).Execute(context.Background(), followingInput(caller, 20))
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if want := []string{brunoNew.PostID, anaNew.PostID, anaOld.PostID}; !slices.Equal(cardIDs(page.Items), want) || page.NextCursor != nil {
		t.Fatalf("page = %v (next %v), want %v and no next page", cardIDs(page.Items), page.NextCursor, want)
	}
	second := page.Items[1]
	if second.Creator.Username != "ana" || !second.Viewer.Liked || !second.Viewer.FollowingCreator || !second.CreatedAt.Equal(anaNew.CreatedAt) {
		t.Fatalf("card = %+v, want the post view of ana's post with the caller's state", second)
	}
	// One read of each read model whatever the page size, as a post read by id.
	for _, name := range []string{"follows", "blocks", "projected", "page", "creators", "collabs", "liked", "shared", "followed", "pageBlocks"} {
		if store.calls[name] != 1 {
			t.Fatalf("calls = %v, want one %s query", store.calls, name)
		}
	}
}

func TestFollowingFeedUsecase_Execute_PagesWithAKeysetCursorAndANullCursorOnTheLastPage(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	ana, bruno := store.projectUser("ana"), store.projectUser("bruno")
	store.follows[caller.String()] = []string{ana, bruno}
	var all []usecase.FeedPost
	for index := range 5 {
		// Two posts share each date, so the post id breaks the tie.
		all = append(all, store.postBy(ana, index/2), store.postBy(bruno, index/2))
	}
	slices.SortFunc(all, newestPostFirst)
	feed := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger())

	input := followingInput(caller, 4)
	var sizes []int
	var served []string
	for range 5 {
		page, err := feed.Execute(context.Background(), input)
		if err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
		sizes = append(sizes, len(page.Items))
		served = append(served, cardIDs(page.Items)...)
		if page.NextCursor == nil {
			break
		}
		cursor, err := usecase.DecodeGridCursor(*page.NextCursor)
		if err != nil {
			t.Fatalf("DecodeGridCursor() error = %v", err)
		}
		last := page.Items[len(page.Items)-1]
		if cursor.PostID != last.PostID || !cursor.CreatedAt.Equal(last.CreatedAt) {
			t.Fatalf("cursor = %+v, want the last row's (created_at, post_id)", cursor)
		}
		input.After = &cursor
	}

	if !slices.Equal(sizes, []int{4, 4, 2}) {
		t.Fatalf("page sizes = %v, want [4 4 2]", sizes)
	}
	if want := cardIDs(cardsOf(all)); !slices.Equal(served, want) {
		t.Fatalf("served = %v, want every post once, newest first: %v", served, want)
	}
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()

	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	return string(raw)
}

func cardsOf(posts []usecase.FeedPost) []usecase.FeedCard {
	cards := make([]usecase.FeedCard, 0, len(posts))
	for _, post := range posts {
		cards = append(cards, usecase.FeedCard{PostID: post.PostID})
	}
	return cards
}

func TestFollowingFeedUsecase_Execute_LeavesOutTheCallerBlockRelatedAndUnprojectedUsersBeforeThePageQuery(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	ana, blocker, blocked := store.projectUser("ana"), store.projectUser("blocker"), store.projectUser("blocked")
	gone := uuid.NewString()
	store.users[caller.String()] = usecase.FeedCreator{UserID: caller.String(), Username: "me"}
	store.follows[caller.String()] = []string{blocker, ana, caller.String(), gone, blocked, ana}
	store.blocks[caller.String()] = []string{blocker, blocked}
	visible := store.postBy(ana, 3)
	for _, userID := range []string{blocker, blocked, caller.String(), gone} {
		store.postBy(userID, 1)
	}

	page, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger()).Execute(context.Background(), followingInput(caller, 1))
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if !slices.Equal(store.checked[0], []string{ana, gone}) {
		t.Fatalf("projection checked for %v, want [ana gone]: no caller, no block, no repeat", store.checked[0])
	}
	if !slices.Equal(store.queries[0].UserIDs, []string{ana}) {
		t.Fatalf("query ids = %v, want only ana", store.queries[0].UserIDs)
	}
	// A full page with no hidden post taking a place in it.
	if !slices.Equal(cardIDs(page.Items), []string{visible.PostID}) || page.NextCursor != nil {
		t.Fatalf("page = %v (next %v), want only ana's post", cardIDs(page.Items), page.NextCursor)
	}
}

func TestFollowingFeedUsecase_Execute_AnswersAnEmptyPageWithoutReadingPostsWhenNobodyIsLeft(t *testing.T) {
	cases := map[string]func(store *fakeFollowingStore, caller string){
		"follows nobody": func(*fakeFollowingStore, string) {},
		"follows only block-related users": func(store *fakeFollowingStore, caller string) {
			blocked := store.projectUser("blocked")
			store.follows[caller] = []string{blocked}
			store.blocks[caller] = []string{blocked}
		},
		"follows only users no longer projected": func(store *fakeFollowingStore, caller string) {
			store.follows[caller] = []string{uuid.NewString()}
		},
	}
	for name, arrange := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeFollowingStore()
			caller := uuid.New()
			arrange(store, caller.String())

			page, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger()).Execute(context.Background(), followingInput(caller, 20))
			if err != nil {
				t.Fatalf("Execute() error = %v", err)
			}

			if page.Items == nil || len(page.Items) != 0 || page.NextCursor != nil {
				t.Fatalf("page = %+v, want items [] and a null cursor", page)
			}
			if store.calls["page"] != 0 || store.calls["creators"] != 0 {
				t.Fatalf("calls = %v, want no post nor hydration read", store.calls)
			}
		})
	}
}

func TestFollowingFeedUsecase_Execute_DropsAPostWhoseCreatorWentAwayAfterTheFollowsWereRead(t *testing.T) {
	store := newFakeFollowingStore()
	caller := uuid.New()
	ana, bruno := store.projectUser("ana"), store.projectUser("bruno")
	store.follows[caller.String()] = []string{ana, bruno}
	store.postBy(bruno, 1)
	kept := store.postBy(ana, 2)
	feed := usecase.NewFollowingFeed(&racingStore{fakeFollowingStore: store, leave: bruno}, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger())

	page, err := feed.Execute(context.Background(), followingInput(caller, 20))
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if !slices.Equal(cardIDs(page.Items), []string{kept.PostID}) {
		t.Fatalf("page = %v, want only the post GetPost would serve", cardIDs(page.Items))
	}
}

// racingStore removes a user from the read model between the page query and the
// hydration, as a concurrent UserDeleted event would.
type racingStore struct {
	*fakeFollowingStore
	leave string
}

func (s *racingStore) FindFollowingPosts(ctx context.Context, query usecase.FollowingPostsQuery) ([]usecase.FeedPost, error) {
	rows, err := s.fakeFollowingStore.FindFollowingPosts(ctx, query)
	delete(s.users, s.leave)
	return rows, err
}

func TestFollowingFeedUsecase_Execute_RejectsAnInvalidInputOrConfiguration(t *testing.T) {
	cases := map[string]struct {
		input      usecase.FollowingFeedInput
		maxFollows int
	}{
		"no caller":        {input: followingInput(uuid.Nil, 20), maxFollows: 2000},
		"limit 0":          {input: followingInput(uuid.New(), 0), maxFollows: 2000},
		"limit above max":  {input: followingInput(uuid.New(), usecase.KeysetMaxLimit+1), maxFollows: 2000},
		"no follows cap":   {input: followingInput(uuid.New(), 20), maxFollows: 0},
		"negative follows": {input: followingInput(uuid.New(), 20), maxFollows: -1},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeFollowingStore()

			_, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: tc.maxFollows}, discardLogger()).Execute(context.Background(), tc.input)

			if err == nil || len(store.calls) != 0 {
				t.Fatalf("Execute() error = %v, calls = %v, want an error before any read", err, store.calls)
			}
		})
	}
}

func TestFollowingFeedUsecase_Execute_PropagatesReadFailures(t *testing.T) {
	for _, name := range []string{"follows", "blocks", "projected", "page", "creators", "liked"} {
		t.Run(name, func(t *testing.T) {
			store := newFakeFollowingStore()
			caller := uuid.New()
			ana := store.projectUser("ana")
			store.follows[caller.String()] = []string{ana}
			store.postBy(ana, 1)
			boom := errors.New("mongo down")
			store.failWith[name] = boom

			_, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 2000}, discardLogger()).Execute(context.Background(), followingInput(caller, 20))

			if !errors.Is(err, boom) {
				t.Fatalf("Execute() error = %v, want %v", err, boom)
			}
		})
	}
}

func TestFollowingFeedPageToResponse_MapsEveryItemAsThePostViewOfThatPost(t *testing.T) {
	description := "Harbour at dusk"
	card := usecase.FeedCard{
		PostID:      uuid.NewString(),
		Creator:     usecase.FeedCreator{UserID: uuid.NewString(), Username: "ana"},
		Description: &description,
		PostType:    usecase.FeedPostTypeBasic,
		Media:       []usecase.FeedMedia{{ID: "m", URL: "https://media.test/a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 1}},
		CreatedAt:   gridEpoch,
		Viewer:      usecase.FeedViewerState{FollowingCreator: true},
	}
	next := "cursor"

	got, err := usecase.FollowingFeedPageToResponse(context.Background(), usecase.FollowingFeedPage{Items: []usecase.FeedCard{card}, NextCursor: &next}, markingSigner{})
	if err != nil {
		t.Fatalf("FollowingFeedPageToResponse() error = %v", err)
	}
	view, err := usecase.PostViewToResponse(context.Background(), card, markingSigner{})
	if err != nil {
		t.Fatalf("PostViewToResponse() error = %v", err)
	}

	if len(got.Items) != 1 || got.NextCursor != &next {
		t.Fatalf("page = %+v, want one item and the cursor", got)
	}
	if gotJSON, wantJSON := mustJSON(t, got.Items[0]), mustJSON(t, view); gotJSON != wantJSON {
		t.Fatalf("item = %s, want the post view %s", gotJSON, wantJSON)
	}
	if got.Items[0].Media[0].URL != "https://media.test/a.jpg?sig=signed" {
		t.Fatalf("media url = %q, want it signed", got.Items[0].Media[0].URL)
	}

	empty, err := usecase.FollowingFeedPageToResponse(context.Background(), usecase.FollowingFeedPage{}, unsignedURLs{})
	if err != nil {
		t.Fatalf("FollowingFeedPageToResponse() error = %v", err)
	}
	if body := mustJSON(t, empty); body != `{"items":[],"nextCursor":null}` {
		t.Fatalf("empty page = %s, want items [] and nextCursor null", body)
	}
}

func TestFollowingFeedPageToResponse_FailsWithoutASignerOrWhenSigningFails(t *testing.T) {
	page := usecase.FollowingFeedPage{Items: []usecase.FeedCard{{
		PostID: uuid.NewString(), Creator: usecase.FeedCreator{UserID: "u", Username: "ana"}, PostType: usecase.FeedPostTypeBasic,
		Media: []usecase.FeedMedia{{ID: "m", URL: "https://media.test/a.jpg", MediaType: usecase.FeedMediaTypeImage, Order: 1}}, CreatedAt: time.Now(),
	}}}

	if _, err := usecase.FollowingFeedPageToResponse(context.Background(), page, nil); err == nil {
		t.Fatal("FollowingFeedPageToResponse() error = nil, want an error without a signer")
	}
	wantErr := errors.New("no delegation key")
	if _, err := usecase.FollowingFeedPageToResponse(context.Background(), page, markingSigner{err: wantErr}); !errors.Is(err, wantErr) {
		t.Fatalf("FollowingFeedPageToResponse() error = %v, want the signing error", err)
	}
}
