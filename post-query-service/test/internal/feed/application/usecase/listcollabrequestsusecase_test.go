package usecase_test

import (
	"cmp"
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// fakeCollabRequestsStore holds one collab's owner and memberships, and serves pages
// the way the repository does: (requested_at, user_id) ascending, strictly after the
// cursor, in one status.
type fakeCollabRequestsStore struct {
	owners  map[string]string
	rows    map[string][]usecase.CollabRequestRow
	users   map[string]usecase.FeedCreator
	queries []usecase.CollabRequestsQuery
	asked   [][]string
	failOn  string
	failErr error
}

func newFakeCollabRequestsStore() *fakeCollabRequestsStore {
	return &fakeCollabRequestsStore{owners: map[string]string{}, rows: map[string][]usecase.CollabRequestRow{}, users: map[string]usecase.FeedCreator{}}
}

func (f *fakeCollabRequestsStore) FindCollabOwner(_ context.Context, collabID string) (string, bool, error) {
	if f.failOn == "owner" {
		return "", false, f.failErr
	}
	owner, found := f.owners[collabID]
	return owner, found, nil
}

func (f *fakeCollabRequestsStore) FindCollabRequests(_ context.Context, query usecase.CollabRequestsQuery) ([]usecase.CollabRequestRow, error) {
	f.queries = append(f.queries, query)
	if f.failOn == "rows" {
		return nil, f.failErr
	}
	var rows []usecase.CollabRequestRow
	for _, row := range f.rows[query.CollabID] {
		if row.Status != query.Status {
			continue
		}
		if after := query.After; after != nil {
			if c := row.RequestedAt.Compare(after.RequestedAt); c < 0 || (c == 0 && row.UserID <= after.UserID) {
				continue
			}
		}
		rows = append(rows, row)
	}
	slices.SortFunc(rows, func(a, b usecase.CollabRequestRow) int {
		return cmp.Or(a.RequestedAt.Compare(b.RequestedAt), strings.Compare(a.UserID, b.UserID))
	})
	return rows[:min(query.Limit, len(rows))], nil
}

func (f *fakeCollabRequestsStore) FindCreators(_ context.Context, userIDs []string) ([]usecase.FeedCreator, error) {
	f.asked = append(f.asked, slices.Clone(userIDs))
	if f.failOn == "users" {
		return nil, f.failErr
	}
	var found []usecase.FeedCreator
	for _, userID := range userIDs {
		if user, ok := f.users[userID]; ok {
			found = append(found, user)
		}
	}
	return found, nil
}

// request stores a membership of collabID requested minutes after 10:00 and its user.
func (f *fakeCollabRequestsStore) request(collabID, status string, minutes int) string {
	userID := uuid.NewString()
	f.users[userID] = usecase.FeedCreator{UserID: userID, Username: "user-" + userID[:4]}
	f.rows[collabID] = append(f.rows[collabID], usecase.CollabRequestRow{
		UserID: userID, Status: status, RequestedAt: time.Date(2026, 3, 1, 10, minutes, 0, 0, time.UTC),
	})
	return userID
}

func TestListCollabRequestsUsecase_Execute_PagesTheOwnersPendingRequestsOldestFirst(t *testing.T) {
	store := newFakeCollabRequestsStore()
	owner, collabID := uuid.New(), uuid.New()
	store.owners[collabID.String()] = owner.String()
	var want []string
	// Five pending requests, two at the same minute (ordered by user id), plus others.
	for _, minutes := range []int{4, 1, 3, 3, 0} {
		want = append(want, store.request(collabID.String(), "PENDING", minutes))
	}
	store.request(collabID.String(), "ACCEPTED", 2)
	store.request(collabID.String(), "REJECTED", 0)
	store.request(uuid.NewString(), "PENDING", 0)
	slices.SortFunc(want, func(a, b string) int {
		minuteOf := func(id string) time.Time {
			for _, row := range store.rows[collabID.String()] {
				if row.UserID == id {
					return row.RequestedAt
				}
			}
			return time.Time{}
		}
		return cmp.Or(minuteOf(a).Compare(minuteOf(b)), strings.Compare(a, b))
	})

	uc := usecase.NewListCollabRequests(store, discardLogger())
	var got []string
	var sizes []int
	var after *usecase.CollabRequestsCursor
	for range 5 {
		page, err := uc.Execute(context.Background(), usecase.ListCollabRequestsInput{UserID: owner, CollabID: collabID, Status: "PENDING", After: after, Limit: 2})
		if err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
		sizes = append(sizes, len(page.Items))
		for _, item := range page.Items {
			if item.Status != "PENDING" || item.User.Username == "" {
				t.Fatalf("item = %+v, want a hydrated PENDING request", item)
			}
			got = append(got, item.User.UserID)
		}
		if page.NextCursor == nil {
			break
		}
		cursor, err := usecase.DecodeCollabRequestsCursor(*page.NextCursor)
		if err != nil {
			t.Fatalf("DecodeCollabRequestsCursor() error = %v", err)
		}
		after = &cursor
	}

	if !slices.Equal(got, want) || !slices.Equal(sizes, []int{2, 2, 1}) {
		t.Fatalf("pages %v = %v, want [2 2 1] = %v oldest first", sizes, got, want)
	}
	if store.queries[0].Limit != 3 || len(store.asked) != 3 {
		t.Fatalf("query limit = %d, user reads = %d, want limit+1 and one $in of users per page", store.queries[0].Limit, len(store.asked))
	}
}

func TestListCollabRequestsUsecase_Execute_AnswersForbiddenToANonOwnerWithoutReadingAnyRequest(t *testing.T) {
	store := newFakeCollabRequestsStore()
	owner, stranger, collabID := uuid.New(), uuid.New(), uuid.New()
	store.owners[collabID.String()] = owner.String()
	store.request(collabID.String(), "PENDING", 0)

	_, err := usecase.NewListCollabRequests(store, discardLogger()).Execute(context.Background(),
		usecase.ListCollabRequestsInput{UserID: stranger, CollabID: collabID, Status: "PENDING", Limit: 20})

	if !errors.Is(err, usecase.ErrNotCollabOwner) {
		t.Fatalf("Execute() error = %v, want ErrNotCollabOwner", err)
	}
	if len(store.queries)+len(store.asked) != 0 {
		t.Fatal("a non-owner's request read memberships or users")
	}
}

func TestListCollabRequestsUsecase_Execute_AnswersNotFoundForAnUnknownCollab(t *testing.T) {
	store := newFakeCollabRequestsStore()

	_, err := usecase.NewListCollabRequests(store, discardLogger()).Execute(context.Background(),
		usecase.ListCollabRequestsInput{UserID: uuid.New(), CollabID: uuid.New(), Status: "PENDING", Limit: 20})

	if !errors.Is(err, usecase.ErrCollabNotFound) || len(store.queries) != 0 {
		t.Fatalf("Execute() error = %v, queries = %d, want ErrCollabNotFound and no read", err, len(store.queries))
	}
}

func TestListCollabRequestsUsecase_Execute_LeavesOutARequesterNoLongerProjectedButKeepsTheCursor(t *testing.T) {
	store := newFakeCollabRequestsStore()
	owner, collabID := uuid.New(), uuid.New()
	store.owners[collabID.String()] = owner.String()
	first := store.request(collabID.String(), "PENDING", 0)
	gone := store.request(collabID.String(), "PENDING", 1)
	store.request(collabID.String(), "PENDING", 2)
	delete(store.users, gone)

	page, err := usecase.NewListCollabRequests(store, discardLogger()).Execute(context.Background(),
		usecase.ListCollabRequestsInput{UserID: owner, CollabID: collabID, Status: "PENDING", Limit: 2})
	if err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(page.Items) != 1 || page.Items[0].User.UserID != first || page.NextCursor == nil {
		t.Fatalf("page = %+v, want only the first requester and a next cursor", page)
	}
	cursor, err := usecase.DecodeCollabRequestsCursor(*page.NextCursor)
	if err != nil || cursor.UserID != gone {
		t.Fatalf("cursor = %+v, %v, want it after the last row read (%s)", cursor, err, gone)
	}
}

func TestListCollabRequestsUsecase_Execute_RejectsInvalidInputAndPropagatesFailures(t *testing.T) {
	valid := usecase.ListCollabRequestsInput{UserID: uuid.New(), CollabID: uuid.New(), Status: "PENDING", Limit: 20}
	for name, mutate := range map[string]func(*usecase.ListCollabRequestsInput){
		"no user":        func(input *usecase.ListCollabRequestsInput) { input.UserID = uuid.Nil },
		"no collab":      func(input *usecase.ListCollabRequestsInput) { input.CollabID = uuid.Nil },
		"unknown status": func(input *usecase.ListCollabRequestsInput) { input.Status = "INVITED" },
		"no status":      func(input *usecase.ListCollabRequestsInput) { input.Status = "" },
		"zero limit":     func(input *usecase.ListCollabRequestsInput) { input.Limit = 0 },
		"limit above 50": func(input *usecase.ListCollabRequestsInput) { input.Limit = 51 },
	} {
		t.Run(name, func(t *testing.T) {
			store := newFakeCollabRequestsStore()
			input := valid
			mutate(&input)
			if _, err := usecase.NewListCollabRequests(store, discardLogger()).Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil, want a validation error")
			}
		})
	}

	failure := errors.New("mongo unavailable")
	for _, step := range []string{"owner", "rows", "users"} {
		store := newFakeCollabRequestsStore()
		store.owners[valid.CollabID.String()] = valid.UserID.String()
		store.request(valid.CollabID.String(), "PENDING", 0)
		store.failOn, store.failErr = step, failure
		if _, err := usecase.NewListCollabRequests(store, discardLogger()).Execute(context.Background(), valid); !errors.Is(err, failure) {
			t.Fatalf("%s failing: Execute() error = %v, want %v", step, err, failure)
		}
	}
}

func TestCollabRequestsCursor_RoundTripsToTheMillisecondAndRejectsOtherListsCursors(t *testing.T) {
	want := usecase.CollabRequestsCursor{RequestedAt: time.Date(2026, 3, 1, 10, 15, 0, 500000000, time.UTC), UserID: uuid.NewString()}
	got, err := usecase.DecodeCollabRequestsCursor(usecase.EncodeCollabRequestsCursor(want))
	if err != nil || got != want {
		t.Fatalf("round trip = %+v, %v, want %+v", got, err, want)
	}

	for name, raw := range map[string]string{
		"empty":           "",
		"not base64url":   "***",
		"a grid cursor":   usecase.EncodeGridCursor(usecase.GridCursor{CreatedAt: want.RequestedAt, PostID: want.UserID}),
		"a reply cursor":  usecase.EncodeRepliesCursor(usecase.RepliesCursor{CreatedAt: want.RequestedAt, CommentID: want.UserID}),
		"missing user":    "eyJyIjoxfQ",
		"too long":        strings.Repeat("a", 300),
		"uppercase uuid":  usecase.EncodeCollabRequestsCursor(usecase.CollabRequestsCursor{RequestedAt: want.RequestedAt, UserID: strings.ToUpper(want.UserID)}),
		"nil uuid":        usecase.EncodeCollabRequestsCursor(usecase.CollabRequestsCursor{RequestedAt: want.RequestedAt, UserID: uuid.Nil.String()}),
		"before the 1970": usecase.EncodeCollabRequestsCursor(usecase.CollabRequestsCursor{RequestedAt: time.Unix(-5, 0), UserID: want.UserID}),
	} {
		if _, err := usecase.DecodeCollabRequestsCursor(raw); !errors.Is(err, usecase.ErrInvalidCursor) {
			t.Fatalf("%s: DecodeCollabRequestsCursor() error = %v, want ErrInvalidCursor", name, err)
		}
	}
}

func TestCollabRequestPageToResponse_SignsTheRequestersPictureAndNeverAnswersANullList(t *testing.T) {
	picture := "ana.jpg"
	page := usecase.CollabRequestPage{Items: []usecase.CollabRequestItem{{
		User: usecase.FeedCreator{UserID: "u-1", Username: "ana", PictureURL: &picture}, Status: "PENDING",
		RequestedAt: time.Date(2026, 3, 1, 10, 15, 0, 0, time.UTC),
	}, {
		User: usecase.FeedCreator{UserID: "u-2", Username: "bruno"}, Status: "PENDING",
	}}}

	response, err := usecase.CollabRequestPageToResponse(context.Background(), page, markingSigner{})
	if err != nil {
		t.Fatalf("CollabRequestPageToResponse() error = %v", err)
	}
	body := toJSONMap(t, response)
	assertKeys(t, "page", body, "items", "nextCursor")
	first := body["items"].([]any)[0].(map[string]any)
	assertKeys(t, "item", first, "user", "status", "requestedAt")
	assertKeys(t, "user", first["user"].(map[string]any), "userId", "username", "pictureUrl")
	if first["user"].(map[string]any)["pictureUrl"] != "ana.jpg?sig=signed" || first["requestedAt"] != "2026-03-01T10:15:00Z" {
		t.Fatalf("first item = %v, want the signed picture and the request date", first)
	}
	if body["items"].([]any)[1].(map[string]any)["user"].(map[string]any)["pictureUrl"] != nil || body["nextCursor"] != nil {
		t.Fatalf("body = %v, want a null picture kept null and a null cursor", body)
	}

	empty := toJSONMap(t, mustCollabRequestPage(t, usecase.CollabRequestPage{}))
	if items, ok := empty["items"].([]any); !ok || len(items) != 0 {
		t.Fatalf("empty page items = %v, want []", empty["items"])
	}
	if _, err := usecase.CollabRequestPageToResponse(context.Background(), page, nil); err == nil {
		t.Fatal("CollabRequestPageToResponse() with no signer error = nil, want an error")
	}
	if _, err := usecase.CollabRequestPageToResponse(context.Background(), page, markingSigner{err: errors.New("no key")}); err == nil {
		t.Fatal("CollabRequestPageToResponse() with a failing signer error = nil, want an error")
	}
}

func mustCollabRequestPage(t *testing.T, page usecase.CollabRequestPage) usecase.CollabRequestPageResponse {
	t.Helper()
	response, err := usecase.CollabRequestPageToResponse(context.Background(), page, unsignedURLs{})
	if err != nil {
		t.Fatalf("CollabRequestPageToResponse() error = %v", err)
	}
	return response
}
