package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeFeedStore is an in-memory feed for one viewer plus the read models a claim
// hydrates from. It implements both claim ports and counts every query.
type fakeFeedStore struct {
	units    []*fakeUnit
	posts    map[string]usecase.FeedPost
	creators map[string]usecase.FeedCreator
	collabs  []usecase.FeedCollab
	liked    []string
	shared   []string
	followed []string
	blocked  []string
	calls    map[string]int
	ctxs     []context.Context
	failOn   string
	failErr  error
}

type fakeUnit struct {
	ref    usecase.FeedUnitRef
	served bool
}

func newFakeFeedStore() *fakeFeedStore {
	return &fakeFeedStore{
		posts:    map[string]usecase.FeedPost{},
		creators: map[string]usecase.FeedCreator{"creator-1": {UserID: "creator-1", Username: "ana"}},
		calls:    map[string]int{},
	}
}

// seed adds count units whose posts exist, at positions firstPosition onwards, and
// returns their post ids.
func (s *fakeFeedStore) seed(firstPosition int64, count int) []string {
	postIDs := make([]string, 0, count)
	for index := range count {
		postID := uuid.NewString()
		postIDs = append(postIDs, postID)
		s.units = append(s.units, &fakeUnit{ref: usecase.FeedUnitRef{PostID: postID, Position: firstPosition + int64(index)}})
		s.posts[postID] = usecase.FeedPost{PostID: postID, CreatorID: "creator-1", PostType: "BASIC", Tags: []string{}, Media: []usecase.FeedMedia{}}
	}
	return postIDs
}

func (s *fakeFeedStore) track(ctx context.Context, name string) error {
	s.calls[name]++
	s.ctxs = append(s.ctxs, ctx)
	if s.failOn == name {
		return s.failErr
	}
	return nil
}

func (s *fakeFeedStore) matching(afterPosition *int64, unservedOnly bool) []*fakeUnit {
	var matched []*fakeUnit
	for _, unit := range s.units {
		if afterPosition != nil && unit.ref.Position <= *afterPosition {
			continue
		}
		if unservedOnly && unit.served {
			continue
		}
		matched = append(matched, unit)
	}
	return matched
}

func (s *fakeFeedStore) FindPosition(ctx context.Context, userID, postID string) (int64, bool, error) {
	if err := s.track(ctx, "FindPosition"); err != nil {
		return 0, false, err
	}
	for _, unit := range s.units {
		if unit.ref.PostID == postID {
			return unit.ref.Position, true, nil
		}
	}
	return 0, false, nil
}

func (s *fakeFeedStore) FindAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool, limit int) ([]usecase.FeedUnitRef, error) {
	if err := s.track(ctx, "FindAfterPosition"); err != nil {
		return nil, err
	}
	var refs []usecase.FeedUnitRef
	for _, unit := range s.matching(afterPosition, unservedOnly) {
		if len(refs) == limit {
			break
		}
		refs = append(refs, unit.ref)
	}
	return refs, nil
}

func (s *fakeFeedStore) HasAfterPosition(ctx context.Context, userID string, afterPosition *int64, unservedOnly bool) (bool, error) {
	if err := s.track(ctx, "HasAfterPosition"); err != nil {
		return false, err
	}
	return len(s.matching(afterPosition, unservedOnly)) > 0, nil
}

func (s *fakeFeedStore) MarkServed(ctx context.Context, userID string, postIDs []string, servedAt time.Time) error {
	if err := s.track(ctx, "MarkServed"); err != nil {
		return err
	}
	for _, unit := range s.units {
		if slices.Contains(postIDs, unit.ref.PostID) {
			unit.served = true
		}
	}
	return nil
}

func (s *fakeFeedStore) FindPosts(ctx context.Context, postIDs []string) ([]usecase.FeedPost, error) {
	if err := s.track(ctx, "FindPosts"); err != nil {
		return nil, err
	}
	var found []usecase.FeedPost
	for _, postID := range postIDs {
		if post, ok := s.posts[postID]; ok {
			found = append(found, post)
		}
	}
	return found, nil
}

func (s *fakeFeedStore) FindCreators(ctx context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	if err := s.track(ctx, "FindCreators"); err != nil {
		return nil, err
	}
	var found []usecase.FeedCreator
	for _, creator := range s.creators {
		if slices.Contains(userIDs, creator.UserID) {
			found = append(found, creator)
		}
	}
	return found, nil
}

func (s *fakeFeedStore) FindCollabs(ctx context.Context, collabIDs, postIDs []string) ([]usecase.FeedCollab, error) {
	if err := s.track(ctx, "FindCollabs"); err != nil {
		return nil, err
	}
	var found []usecase.FeedCollab
	for _, collab := range s.collabs {
		if slices.Contains(collabIDs, collab.CollabID) || slices.Contains(postIDs, collab.PostID) {
			found = append(found, collab)
		}
	}
	return found, nil
}

func (s *fakeFeedStore) FindLikedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error) {
	return s.liked, s.track(ctx, "FindLikedPostIDs")
}

func (s *fakeFeedStore) FindSharedPostIDs(ctx context.Context, viewerID string, postIDs []string) ([]string, error) {
	return s.shared, s.track(ctx, "FindSharedPostIDs")
}

func (s *fakeFeedStore) FindFollowedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error) {
	return s.followed, s.track(ctx, "FindFollowedUserIDs")
}

func (s *fakeFeedStore) FindBlockRelatedUserIDs(ctx context.Context, viewerID string, userIDs []string) ([]string, error) {
	return s.blocked, s.track(ctx, "FindBlockRelatedUserIDs")
}

var defaultClaimConfig = usecase.ClaimFeedConfig{DefaultPageSize: 15, MaxPageSize: 30, MaxReads: 3}

func cardPostIDs(cards []usecase.FeedCard) []string {
	postIDs := make([]string, 0, len(cards))
	for _, card := range cards {
		postIDs = append(postIDs, card.PostID)
	}
	return postIDs
}

func claim(t *testing.T, store *fakeFeedStore, input usecase.ClaimFeedInput) usecase.ClaimFeedOutput {
	t.Helper()

	uc := usecase.NewClaimFeed(store, store, defaultClaimConfig, discardLogger())
	if input.UserID == uuid.Nil {
		input.UserID = uuid.New()
	}
	output, err := uc.Execute(context.Background(), input)
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	return output
}

func parseCursor(t *testing.T, postID string) *uuid.UUID {
	t.Helper()
	cursor, err := uuid.Parse(postID)
	if err != nil {
		t.Fatalf("uuid.Parse() error = %v", err)
	}
	return &cursor
}

func TestClaimFeedUsecase_Execute_ReturnsTheFirstFifteenUnitsInPositionOrderOnANewFeed(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 20)

	output := claim(t, store, usecase.ClaimFeedInput{})

	if got := cardPostIDs(output.Items); !slices.Equal(got, postIDs[:15]) {
		t.Fatalf("items = %v, want the first 15 in order", got)
	}
	if !output.HasMore || output.LastSeenID == nil || *output.LastSeenID != postIDs[14] {
		t.Fatalf("hasMore = %v, lastSeenId = %v", output.HasMore, output.LastSeenID)
	}
}

func TestClaimFeedUsecase_Execute_StartsFromTheLowestUnservedPositionWithoutACursor(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 20)

	claim(t, store, usecase.ClaimFeedInput{})
	second := claim(t, store, usecase.ClaimFeedInput{})

	if got := cardPostIDs(second.Items); !slices.Equal(got, postIDs[15:]) {
		t.Fatalf("second session = %v, want only unserved items", got)
	}
	if second.HasMore {
		t.Fatal("hasMore = true, want false: nothing remains")
	}
}

func TestClaimFeedUsecase_Execute_ReturnsExactlyTheRemainingTenWithoutPadding(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 20)

	output := claim(t, store, usecase.ClaimFeedInput{LastSeenID: parseCursor(t, postIDs[9])})

	if got := cardPostIDs(output.Items); !slices.Equal(got, postIDs[10:]) {
		t.Fatalf("items = %v, want exactly the 10 after the cursor", got)
	}
	if output.HasMore {
		t.Fatal("hasMore = true, want false")
	}
	if store.calls["HasAfterPosition"] != 0 {
		t.Fatal("a short read already proves nothing remains")
	}
}

func TestClaimFeedUsecase_Execute_RepeatingACursorReturnsTheSamePage(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 40)
	cursor := parseCursor(t, postIDs[4])

	first := claim(t, store, usecase.ClaimFeedInput{LastSeenID: cursor})
	second := claim(t, store, usecase.ClaimFeedInput{LastSeenID: cursor})

	if !slices.Equal(cardPostIDs(first.Items), cardPostIDs(second.Items)) {
		t.Fatalf("first = %v, second = %v", cardPostIDs(first.Items), cardPostIDs(second.Items))
	}
	if !first.HasMore || !second.HasMore {
		t.Fatal("hasMore = false, want true")
	}
}

func TestClaimFeedUsecase_Execute_RestartsFromTheLowestUnservedPositionAndWarnsOnACursorMiss(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 20)
	claim(t, store, usecase.ClaimFeedInput{Limit: pointer(5)})

	logger, logs := bufferLogger()
	uc := usecase.NewClaimFeed(store, store, defaultClaimConfig, logger)
	unknown := uuid.New()
	output, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: uuid.New(), LastSeenID: &unknown, CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if got := cardPostIDs(output.Items); !slices.Equal(got, postIDs[5:20]) {
		t.Fatalf("items = %v, want to start at the lowest unserved position", got)
	}
	for _, want := range []string{"level=WARN", "feed cursor not found", "correlation_id=corr-1", "lastSeenId=" + unknown.String()} {
		if !bytes.Contains(logs.Bytes(), []byte(want)) {
			t.Fatalf("log misses %q: %s", want, logs)
		}
	}
}

func TestClaimFeedUsecase_Execute_ValidatesTheLimit(t *testing.T) {
	for _, limit := range []int{0, -1, 31} {
		t.Run(fmt.Sprintf("limit %d", limit), func(t *testing.T) {
			store := newFakeFeedStore()
			uc := usecase.NewClaimFeed(store, store, defaultClaimConfig, discardLogger())

			_, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: uuid.New(), Limit: &limit})
			if !errors.Is(err, usecase.ErrClaimFeedInvalidLimit) {
				t.Fatalf("Execute() error = %v, want ErrClaimFeedInvalidLimit", err)
			}
			if len(store.calls) != 0 {
				t.Fatalf("calls = %v, want none", store.calls)
			}
		})
	}

	t.Run("a limit up to the maximum is honoured", func(t *testing.T) {
		store := newFakeFeedStore()
		store.seed(0, 40)
		if output := claim(t, store, usecase.ClaimFeedInput{Limit: pointer(30)}); len(output.Items) != 30 {
			t.Fatalf("items = %d, want 30", len(output.Items))
		}
	})
}

func TestClaimFeedUsecase_Execute_RejectsAMissingUserID(t *testing.T) {
	store := newFakeFeedStore()
	uc := usecase.NewClaimFeed(store, store, defaultClaimConfig, discardLogger())

	if _, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{}); err == nil {
		t.Fatal("Execute() error = nil, want an error")
	}
}

func TestClaimFeedUsecase_Execute_OverlaysCurrentStateWithOneQueryPerCollection(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 3)
	store.creators["creator-1"] = usecase.FeedCreator{UserID: "creator-1", Username: "new_name", PictureURL: pointer("new.jpg")}
	post := store.posts[postIDs[0]]
	post.Stats = usecase.FeedStats{LikeCount: 5, CommentCount: 6, ShareCount: 7}
	post.Description = pointer("Fresh")
	post.Tags = []string{"fresh"}
	post.Media = []usecase.FeedMedia{{ID: "m1", URL: "a.mp4", ThumbnailURL: pointer("a.jpg"), MediaType: "VIDEO", Order: 1, DurationMS: pointer(int64(9000))}}
	post.CollabID = pointer("collab-1")
	store.posts[postIDs[0]] = post
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: postIDs[0], Title: pointer("Harbour"), Status: pointer("OPEN")}}
	store.liked = []string{postIDs[0]}
	store.shared = []string{postIDs[1]}
	store.followed = []string{"creator-1"}

	output := claim(t, store, usecase.ClaimFeedInput{Limit: pointer(3)})

	for _, query := range []string{"FindPosts", "FindCreators", "FindCollabs", "FindLikedPostIDs", "FindSharedPostIDs", "FindFollowedUserIDs", "FindBlockRelatedUserIDs"} {
		if store.calls[query] != 1 {
			t.Fatalf("%s calls = %d, want 1 for the whole page", query, store.calls[query])
		}
	}

	card := output.Items[0]
	if card.Stats.LikeCount != 5 || card.Stats.CommentCount != 6 || card.Stats.ShareCount != 7 {
		t.Fatalf("stats = %+v", card.Stats)
	}
	if card.Creator.Username != "new_name" || *card.Creator.PictureURL != "new.jpg" {
		t.Fatalf("creator = %+v", card.Creator)
	}
	if *card.Description != "Fresh" || card.Tags[0] != "fresh" || card.Media[0].ID != "m1" {
		t.Fatalf("content = %+v", card)
	}
	if *card.CoverThumbnailURL != "a.jpg" || *card.DurationMS != 9000 {
		t.Fatalf("derived fields: cover %v, duration %v", *card.CoverThumbnailURL, *card.DurationMS)
	}
	if *card.CollabTitle != "Harbour" || *card.CollabStatus != "OPEN" || card.PostType != "COLLAB" {
		t.Fatalf("collab = %+v", card)
	}
	if !card.Viewer.Liked || card.Viewer.Shared || !card.Viewer.FollowingCreator {
		t.Fatalf("viewer of the first card = %+v", card.Viewer)
	}
	if viewer := output.Items[1].Viewer; viewer.Liked || !viewer.Shared || !viewer.FollowingCreator {
		t.Fatalf("viewer of the second card = %+v", viewer)
	}
}

func TestClaimFeedUsecase_Execute_FindsTheCollabOfAPostThatDoesNotNameIt(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 1)
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: postIDs[0], Title: pointer("Harbour"), Status: pointer("OPEN")}}

	output := claim(t, store, usecase.ClaimFeedInput{})

	card := output.Items[0]
	if card.PostType != "COLLAB" || card.CollabID == nil || *card.CollabID != "collab-1" || *card.CollabTitle != "Harbour" {
		t.Fatalf("card = %+v, want the collab found through its post_id", card)
	}
}

func TestClaimFeedUsecase_Execute_DropsDeletedPostsCreatorsAndBlocksAndReadsFurtherToFillThePage(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 12)

	delete(store.posts, postIDs[0])
	blockedPost := store.posts[postIDs[1]]
	blockedPost.CreatorID = "blocked-creator"
	store.posts[postIDs[1]] = blockedPost
	store.creators["blocked-creator"] = usecase.FeedCreator{UserID: "blocked-creator", Username: "blocked"}
	store.blocked = []string{"blocked-creator"}
	orphanPost := store.posts[postIDs[2]]
	orphanPost.CreatorID = "deleted-creator"
	store.posts[postIDs[2]] = orphanPost

	logger, logs := bufferLogger()
	uc := usecase.NewClaimFeed(store, store, defaultClaimConfig, logger)
	output, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: uuid.New(), Limit: pointer(5), CorrelationID: "corr-1"})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if got := cardPostIDs(output.Items); !slices.Equal(got, postIDs[3:8]) {
		t.Fatalf("items = %v, want the page filled past the dropped ones", got)
	}
	if store.calls["FindAfterPosition"] != 2 {
		t.Fatalf("reads = %d, want 2", store.calls["FindAfterPosition"])
	}
	if !output.HasMore {
		t.Fatal("hasMore = false, want true")
	}
	for _, want := range []string{"level=WARN", "feed items dropped at claim", "post_missing=1", "creator_missing=1", "blocked=1", "correlation_id=corr-1"} {
		if !bytes.Contains(logs.Bytes(), []byte(want)) {
			t.Fatalf("log misses %q: %s", want, logs)
		}
	}
}

func TestClaimFeedUsecase_Execute_StopsReadingAfterTheConfiguredReadsAndReturnsAShortPage(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 30)
	for _, postID := range postIDs[:2*5] {
		delete(store.posts, postID)
	}

	uc := usecase.NewClaimFeed(store, store, usecase.ClaimFeedConfig{DefaultPageSize: 5, MaxPageSize: 5, MaxReads: 2}, discardLogger())
	output, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: uuid.New()})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(output.Items) != 0 || output.LastSeenID != nil {
		t.Fatalf("output = %+v, want an empty page", output)
	}
	if store.calls["FindAfterPosition"] != 2 {
		t.Fatalf("reads = %d, want the bound of 2", store.calls["FindAfterPosition"])
	}
	if !output.HasMore {
		t.Fatal("hasMore = false, want true: stored items remain")
	}
}

// Dropped units must not keep costing reads: otherwise enough of them at the head of
// a feed would leave the user with empty pages at every session start.
func TestClaimFeedUsecase_Execute_ANewSessionMovesPastUnitsDroppedByAnEarlierClaim(t *testing.T) {
	store := newFakeFeedStore()
	postIDs := store.seed(0, 15)
	for _, postID := range postIDs[:10] {
		delete(store.posts, postID)
	}

	uc := usecase.NewClaimFeed(store, store, usecase.ClaimFeedConfig{DefaultPageSize: 5, MaxPageSize: 5, MaxReads: 2}, discardLogger())
	viewer := uuid.New()
	if _, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: viewer}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	output, err := uc.Execute(context.Background(), usecase.ClaimFeedInput{UserID: viewer})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if got := cardPostIDs(output.Items); !slices.Equal(got, postIDs[10:]) {
		t.Fatalf("items = %v, want the units after the dropped ones", got)
	}
}

func TestClaimFeedUsecase_Execute_MarksTheReturnedUnitsServedOnly(t *testing.T) {
	store := newFakeFeedStore()
	store.seed(0, 20)

	claim(t, store, usecase.ClaimFeedInput{Limit: pointer(5)})

	served := 0
	for index, unit := range store.units {
		if unit.served {
			served++
			if index >= 5 {
				t.Fatalf("unit at index %d was marked served", index)
			}
		}
	}
	if served != 5 {
		t.Fatalf("served units = %d, want 5", served)
	}
}

func TestClaimFeedUsecase_Execute_AnEmptyFeedIsAValidEmptyPage(t *testing.T) {
	store := newFakeFeedStore()

	output := claim(t, store, usecase.ClaimFeedInput{})

	if len(output.Items) != 0 || output.LastSeenID != nil || output.HasMore {
		t.Fatalf("output = %+v, want an empty page", output)
	}
	if store.calls["MarkServed"] != 0 || store.calls["FindPosts"] != 0 {
		t.Fatalf("calls = %v, want no hydration or write", store.calls)
	}
}

func TestClaimFeedUsecase_Execute_PropagatesTheContextAndEveryFailure(t *testing.T) {
	wantErr := errors.New("mongo failed")
	queries := []string{
		"FindPosition", "FindAfterPosition", "HasAfterPosition", "MarkServed", "FindPosts", "FindCreators",
		"FindCollabs", "FindLikedPostIDs", "FindSharedPostIDs", "FindFollowedUserIDs", "FindBlockRelatedUserIDs",
	}

	newInput := func(store *fakeFeedStore) usecase.ClaimFeedInput {
		postIDs := store.seed(0, 20)
		return usecase.ClaimFeedInput{UserID: uuid.New(), LastSeenID: parseCursor(t, postIDs[0]), Limit: pointer(3)}
	}

	t.Run("context", func(t *testing.T) {
		store := newFakeFeedStore()
		input := newInput(store)
		ctx := context.WithValue(context.Background(), contextKey{}, "propagated")
		if _, err := usecase.NewClaimFeed(store, store, defaultClaimConfig, discardLogger()).Execute(ctx, input); err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
		if len(store.ctxs) != len(queries) {
			t.Fatalf("repository calls = %d (%v), want %d", len(store.ctxs), store.calls, len(queries))
		}
		for index, got := range store.ctxs {
			if got.Value(contextKey{}) != "propagated" {
				t.Fatalf("repository call %d did not receive the caller's context", index)
			}
		}
	})

	for _, query := range queries {
		t.Run(query, func(t *testing.T) {
			store := newFakeFeedStore()
			input := newInput(store)
			store.failOn, store.failErr = query, wantErr
			_, err := usecase.NewClaimFeed(store, store, defaultClaimConfig, discardLogger()).Execute(context.Background(), input)
			if !errors.Is(err, wantErr) {
				t.Fatalf("Execute() error = %v, want it to wrap %v", err, wantErr)
			}
		})
	}
}
