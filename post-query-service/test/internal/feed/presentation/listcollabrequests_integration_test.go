package presentation_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
)

type collabRequestsBody struct {
	Items []struct {
		User struct {
			UserID     string  `json:"userId"`
			Username   string  `json:"username"`
			PictureURL *string `json:"pictureUrl"`
		} `json:"user"`
		Status      string    `json:"status"`
		RequestedAt time.Time `json:"requestedAt"`
	} `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func listCollabRequests(t *testing.T, mux *http.ServeMux, target string) collabRequestsBody {
	t.Helper()

	recorder := serve(mux, http.MethodGet, target, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET %s status = %d: %s", target, recorder.Code, recorder.Body)
	}
	var body collabRequestsBody
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return body
}

func collabRequestsPath(collabID uuid.UUID, query string) string {
	return "/api/content/collabs/" + collabID.String() + "/requests" + query
}

// Acceptance: the owner lists the pending requests oldest first; anyone else gets 403.
func TestListCollabRequests_ListsPendingRequestsOldestFirstForTheOwnerAnd403ForOthers(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	owner := app.registerUser(t, ctx, "marta")
	postID := app.insertPostBy(t, ctx, owner, commentsEpoch)
	collabID := app.openCollab(t, ctx, owner, postID)

	// Five pending requests, two in the same millisecond, created out of order; one
	// accepted and one declined that the default filter leaves out.
	minutes := []int{4, 1, 3, 3, 0}
	requested := map[string]time.Time{}
	for index, minute := range minutes {
		userID := app.registerUser(t, ctx, fmt.Sprintf("requester%d", index))
		requested[userID.String()] = commentsEpoch.Add(time.Duration(minute) * time.Minute)
		app.applyMember(t, ctx, collabID, userID, "PENDING", requested[userID.String()], requested[userID.String()])
	}
	accepted := app.registerUser(t, ctx, "accepted")
	app.applyMember(t, ctx, collabID, accepted, "ACCEPTED", commentsEpoch, commentsEpoch.Add(time.Hour))
	app.applyMember(t, ctx, collabID, app.registerUser(t, ctx, "declined"), "REJECTED", commentsEpoch, commentsEpoch.Add(time.Hour))
	want := make([]string, 0, len(requested))
	for userID := range requested {
		want = append(want, userID)
	}
	slices.SortFunc(want, func(a, b string) int {
		if c := requested[a].Compare(requested[b]); c != 0 {
			return c
		}
		return strings.Compare(a, b)
	})

	ownerMux := app.muxFor(t, owner)
	var got []string
	var sizes []int
	target := collabRequestsPath(collabID, "?limit=2")
	for range 5 {
		page := listCollabRequests(t, ownerMux, target)
		sizes = append(sizes, len(page.Items))
		for _, item := range page.Items {
			if item.Status != "PENDING" || item.User.Username == "" || !item.RequestedAt.Equal(requested[item.User.UserID]) {
				t.Fatalf("item = %+v, want a PENDING request with its user and request date", item)
			}
			got = append(got, item.User.UserID)
		}
		if page.NextCursor == nil {
			break
		}
		target = collabRequestsPath(collabID, "?limit=2&cursor="+*page.NextCursor)
	}
	if !slices.Equal(got, want) || !slices.Equal(sizes, []int{2, 2, 1}) {
		t.Fatalf("pages %v = %v, want [2 2 1] = %v, oldest first", sizes, got, want)
	}

	if page := listCollabRequests(t, ownerMux, collabRequestsPath(collabID, "?status=ACCEPTED")); len(page.Items) != 1 || page.Items[0].User.UserID != accepted.String() || page.NextCursor != nil {
		t.Fatalf("ACCEPTED page = %+v, want only the accepted member", page)
	}

	// Everyone but the owner: 403 not_collab_owner, the viewer and a requester alike.
	for name, mux := range map[string]*http.ServeMux{"a stranger": app.mux, "a requester": app.muxFor(t, uuid.MustParse(want[0]))} {
		recorder := serve(mux, http.MethodGet, collabRequestsPath(collabID, ""), "", nil)
		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusForbidden, "not_collab_owner")
		if strings.Contains(recorder.Body.String(), want[0]) {
			t.Fatalf("%s's 403 leaks a requester: %s", name, recorder.Body)
		}
	}

	recorder := serve(ownerMux, http.MethodGet, collabRequestsPath(uuid.New(), ""), "", nil)
	assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusNotFound, "collab_not_found")
	recorder = serve(ownerMux, http.MethodGet, "/api/content/collabs/not-a-uuid/requests", "", nil)
	assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, "invalid_collab_id")
	for query, code := range map[string]string{"?cursor=garbage!": "invalid_cursor", "?limit=51": "invalid_limit", "?status=INVITED": "invalid_status"} {
		recorder = serve(ownerMux, http.MethodGet, collabRequestsPath(collabID, query), "", nil)
		assertErrorBody(t, recorder.Code, recorder.Body.Bytes(), http.StatusBadRequest, code)
	}
}

const collabRequestsKeysetBranches = 2

func TestListCollabRequests_EveryPageIsAnIndexSeek(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	app := newFeedApp(t, ctx)
	owner := app.registerUser(t, ctx, "marta")
	collabID := app.openCollab(t, ctx, owner, app.insertPostBy(t, ctx, owner, commentsEpoch))
	const total, limit = 300, 20
	for index := range total {
		// Ten requests share each date, so most pages start inside a tie; other statuses
		// interleave in the same collab.
		userID := app.registerUser(t, ctx, fmt.Sprintf("requester%d", index))
		requestedAt := commentsEpoch.Add(time.Duration(index/10) * time.Minute)
		app.applyMember(t, ctx, collabID, userID, "PENDING", requestedAt, requestedAt)
		app.applyMember(t, ctx, collabID, uuid.New(), "ACCEPTED", requestedAt, requestedAt)
	}

	ownerMux := app.muxFor(t, owner)
	measured := map[int][]profiledQuery{}
	target := collabRequestsPath(collabID, fmt.Sprintf("?limit=%d", limit))
	for number := 1; number <= total/limit; number++ {
		profile := number == 1 || number == 2 || number == total/limit
		if profile {
			restartProfiler(t, ctx, app.db)
		}
		page := listCollabRequests(t, ownerMux, target)
		if profile {
			measured[number] = pageQueries(t, ctx, app.db, "collab_members")
		}
		if len(page.Items) != limit {
			t.Fatalf("page %d has %d items, want %d", number, len(page.Items), limit)
		}
		if number == total/limit {
			if page.NextCursor != nil {
				t.Fatalf("the last page has a next cursor %q", *page.NextCursor)
			}
			break
		}
		target = collabRequestsPath(collabID, fmt.Sprintf("?limit=%d&cursor=%s", limit, *page.NextCursor))
	}

	keys := map[int]int64{}
	for number, queries := range measured {
		keys[number] = assertIndexSeeks(t, ctx, app.db, fmt.Sprintf("requests page %d", number), queries, collabmongo.MemberRequestsIndex, collabRequestsKeysetBranches, limit)
	}
	if last := total / limit; keys[last] > keys[2]+collabRequestsKeysetBranches {
		t.Fatalf("page %d examined %d keys, page 2 %d: a deep page must cost what an early one costs", last, keys[last], keys[2])
	}
}
