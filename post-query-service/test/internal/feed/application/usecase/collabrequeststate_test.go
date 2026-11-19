package usecase_test

import (
	"context"
	"slices"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// seedCollabPosts gives every unit of store a collab of its own, owned by ownerID, and
// returns the collab ids in unit order.
func seedCollabPosts(store *fakeFeedStore, postIDs []string, ownerID string) []string {
	collabIDs := make([]string, 0, len(postIDs))
	for _, postID := range postIDs {
		collabID := uuid.NewString()
		collabIDs = append(collabIDs, collabID)
		store.collabs = append(store.collabs, usecase.FeedCollab{
			CollabID: collabID, PostID: postID, OwnerUserID: ownerID, Title: pointer("Harbour"), Status: pointer("OPEN"),
		})
	}
	return collabIDs
}

func TestClaimFeedUsecase_Execute_PendingRequestCountIsNullForNonOwners(t *testing.T) {
	owner, stranger := uuid.New(), uuid.New()
	store := newFakeFeedStore()
	postIDs := store.seed(0, 2)
	collabIDs := seedCollabPosts(store, postIDs, owner.String())
	store.pending = map[string]int64{collabIDs[0]: 3}

	ownerPage := claim(t, store, usecase.ClaimFeedInput{UserID: owner})
	if got := ownerPage.Items[0].CollabPendingRequestCount; got == nil || *got != 3 {
		t.Fatalf("owner's count = %v, want 3", got)
	}
	if got := ownerPage.Items[1].CollabPendingRequestCount; got == nil || *got != 0 {
		t.Fatalf("owner's count without pending requests = %v, want 0, not null", got)
	}

	for _, unit := range store.units {
		unit.served = false
	}
	store.pendingAsked = nil
	strangerPage := claim(t, store, usecase.ClaimFeedInput{UserID: stranger})
	for _, card := range strangerPage.Items {
		if card.CollabPendingRequestCount != nil {
			t.Fatalf("non-owner's count = %d, want null", *card.CollabPendingRequestCount)
		}
	}
	if len(store.pendingAsked) != 0 {
		t.Fatalf("pending counts asked for %v, want no count read for a viewer who owns none of the collabs", store.pendingAsked)
	}
}

func TestClaimFeedUsecase_Execute_ShowsTheCallersRequestStatusAndBatchesBothLookups(t *testing.T) {
	viewer, owner := uuid.New(), uuid.New()
	store := newFakeFeedStore()
	postIDs := store.seed(0, 6)
	collabIDs := seedCollabPosts(store, postIDs[:4], owner.String())
	// The viewer owns the collab of the fourth post, and asked to join the first two.
	store.collabs[3].OwnerUserID = viewer.String()
	store.memberships = map[string][]usecase.FeedCollabMembership{
		viewer.String(): {{CollabID: collabIDs[0], Status: "PENDING"}, {CollabID: collabIDs[1], Status: "REJECTED"}},
		owner.String():  {{CollabID: collabIDs[2], Status: "ACCEPTED"}},
	}
	store.pending = map[string]int64{collabIDs[0]: 5, collabIDs[3]: 2}

	page := claim(t, store, usecase.ClaimFeedInput{UserID: viewer})

	statuses := make([]*string, 0, len(page.Items))
	for _, card := range page.Items {
		statuses = append(statuses, card.Viewer.CollabRequestStatus)
	}
	if statuses[0] == nil || *statuses[0] != "PENDING" || statuses[1] == nil || *statuses[1] != "REJECTED" {
		t.Fatalf("statuses = %v, want PENDING then REJECTED", statuses)
	}
	for index := 2; index < len(statuses); index++ {
		if statuses[index] != nil {
			t.Fatalf("status of item %d = %s, want null: someone else's membership, no membership or a BASIC post", index, *statuses[index])
		}
	}
	for index, card := range page.Items {
		if index == 3 {
			if card.CollabPendingRequestCount == nil || *card.CollabPendingRequestCount != 2 {
				t.Fatalf("own collab's count = %v, want 2", card.CollabPendingRequestCount)
			}
			continue
		}
		if card.CollabPendingRequestCount != nil {
			t.Fatalf("count of item %d = %d, want null for a collab the viewer does not own", index, *card.CollabPendingRequestCount)
		}
	}

	if store.calls["FindCollabMemberships"] != 1 || store.calls["CountPendingCollabRequests"] != 1 {
		t.Fatalf("memberships calls = %d, count calls = %d, want one batched call each for the page",
			store.calls["FindCollabMemberships"], store.calls["CountPendingCollabRequests"])
	}
	if !slices.Equal(store.pendingAsked[0], []string{collabIDs[3]}) {
		t.Fatalf("pending counts asked for %v, want only the viewer's own collab %s", store.pendingAsked[0], collabIDs[3])
	}
}

func TestClaimFeedUsecase_Execute_ReadsNoMembershipForAPageWithoutCollabs(t *testing.T) {
	store := newFakeFeedStore()
	store.seed(0, 3)

	claim(t, store, usecase.ClaimFeedInput{})

	if store.calls["FindCollabMemberships"] != 0 || store.calls["CountPendingCollabRequests"] != 0 {
		t.Fatalf("calls = %v, want no collab_members read when no post has a collab", store.calls)
	}
}

func TestGetPostUsecase_Execute_CarriesTheCollabRequestStateAndPropagatesItsFailures(t *testing.T) {
	viewer, owner := uuid.New(), uuid.New()
	store := newFakeFeedStore()
	postID := seedPostView(store)
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: postID.String(), OwnerUserID: owner.String(), Status: pointer("OPEN")}}
	store.memberships = map[string][]usecase.FeedCollabMembership{viewer.String(): {{CollabID: "collab-1", Status: "BANNED"}}}
	store.pending = map[string]int64{"collab-1": 4}
	getPost := usecase.NewGetPost(store, discardLogger())

	card, err := getPost.Execute(context.Background(), usecase.GetPostInput{UserID: viewer, PostID: postID})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if card.Viewer.CollabRequestStatus == nil || *card.Viewer.CollabRequestStatus != "BANNED" || card.CollabPendingRequestCount != nil {
		t.Fatalf("viewer's card: status = %v, count = %v, want BANNED and null", card.Viewer.CollabRequestStatus, card.CollabPendingRequestCount)
	}

	card, err = getPost.Execute(context.Background(), usecase.GetPostInput{UserID: owner, PostID: postID})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if card.Viewer.CollabRequestStatus != nil || card.CollabPendingRequestCount == nil || *card.CollabPendingRequestCount != 4 {
		t.Fatalf("owner's card: status = %v, count = %v, want null and 4", card.Viewer.CollabRequestStatus, card.CollabPendingRequestCount)
	}

	for _, method := range []string{"FindCollabMemberships", "CountPendingCollabRequests"} {
		store.failOn, store.failErr = method, context.DeadlineExceeded
		if _, err := getPost.Execute(context.Background(), usecase.GetPostInput{UserID: owner, PostID: postID}); err == nil {
			t.Fatalf("Execute() with %s failing error = nil, want the failure", method)
		}
	}
}

func TestFollowingFeedUsecase_Execute_CarriesTheCollabRequestState(t *testing.T) {
	store := newFakeFollowingStore()
	viewer := uuid.New()
	ana := store.projectUser("ana")
	store.follows[viewer.String()] = []string{ana}
	post := store.postBy(ana, 1)
	store.collabs = []usecase.FeedCollab{{CollabID: "collab-1", PostID: post.PostID, OwnerUserID: ana, Status: pointer("OPEN")}}
	store.memberships = map[string][]usecase.FeedCollabMembership{viewer.String(): {{CollabID: "collab-1", Status: "PENDING"}}}
	store.pending = map[string]int64{"collab-1": 1}

	page, err := usecase.NewFollowingFeed(store, usecase.FollowingFeedConfig{MaxFollows: 10}, discardLogger()).
		Execute(context.Background(), usecase.FollowingFeedInput{UserID: viewer, Limit: 20})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("items = %d, want 1", len(page.Items))
	}
	card := page.Items[0]
	if card.Viewer.CollabRequestStatus == nil || *card.Viewer.CollabRequestStatus != "PENDING" || card.CollabPendingRequestCount != nil {
		t.Fatalf("status = %v, count = %v, want PENDING and null (the viewer does not own the collab)", card.Viewer.CollabRequestStatus, card.CollabPendingRequestCount)
	}
	if store.calls["pending"] != 0 {
		t.Fatalf("pending count calls = %d, want 0: the viewer owns no collab of the page", store.calls["pending"])
	}
}
