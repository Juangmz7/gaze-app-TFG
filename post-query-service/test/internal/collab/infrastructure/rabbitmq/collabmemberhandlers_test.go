package rabbitmq_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

// The exact JSON post-command-service's six membership event records produce (Jackson,
// camelCase, Instants as ISO-8601, enums by name). Literals, never a marshalled handler
// struct. Each one is a step of one membership: requested at 10:15:00.5, then changed.
const (
	literalCollabJoinRequestCreatedJSON  = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:15:00.512345Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","status":"PENDING","role":"MEMBER","createdAt":"2026-03-01T10:15:00.5Z"}`
	literalCollabJoinRequestAcceptedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:20:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","acceptedBy":"55555555-5555-4555-8555-555555555555","collabMemberStatus":"ACCEPTED","role":"MEMBER","memberCreatedAt":"2026-03-01T10:15:00.5Z"}`
	literalCollabJoinRequestDeclinedJSON = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:20:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","declinedBy":"55555555-5555-4555-8555-555555555555","collabMemberStatus":"REJECTED","role":"MEMBER","memberCreatedAt":"2026-03-01T10:15:00.5Z"}`
	literalCollabJoinRequestDeletedJSON  = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:20:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","deletedBy":"44444444-4444-4444-8444-444444444444","collabMemberStatus":"DELETED","role":"MEMBER","memberCreatedAt":"2026-03-01T10:15:00.5Z"}`
	literalCollabMemberLeftJSON          = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:20:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","collabMemberStatus":"LEFT","role":"MEMBER","createdAt":"2026-03-01T10:15:00.5Z"}`
	literalCollabMemberBannedJSON        = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:20:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","userId":"44444444-4444-4444-8444-444444444444","collabMemberStatus":"BANNED","role":"MEMBER","memberCreatedAt":"2026-03-01T10:15:00.5Z"}`
)

var (
	memberCollabID    = uuid.MustParse("66666666-6666-4666-8666-666666666666")
	memberUserID      = uuid.MustParse("44444444-4444-4444-8444-444444444444")
	memberRequestedAt = time.Date(2026, 3, 1, 10, 15, 0, 500000000, time.UTC)
)

type fakeCollabMemberIdempotency struct {
	processed map[uuid.UUID]bool
	marks     int
	markedCtx context.Context
}

func newFakeCollabMemberIdempotency() *fakeCollabMemberIdempotency {
	return &fakeCollabMemberIdempotency{processed: map[uuid.UUID]bool{}}
}

func (f *fakeCollabMemberIdempotency) IsProcessed(_ context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], nil
}

func (f *fakeCollabMemberIdempotency) MarkProcessed(ctx context.Context, eventID, _ uuid.UUID, _ string) error {
	f.marks++
	f.markedCtx = ctx
	f.processed[eventID] = true
	return nil
}

type collabMemberTransactionKey struct{}

// fakeCollabMemberTransactor marks the context it hands to fn, the way a real
// transaction carries its session.
type fakeCollabMemberTransactor struct{}

func (fakeCollabMemberTransactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(context.WithValue(ctx, collabMemberTransactionKey{}, true))
}

type collabMemberRow struct {
	status, role         string
	requestedAt, updated time.Time
	version              int64
}

// fakeCollabMemberStore stands in for collab_members behind the real usecase.
type fakeCollabMemberStore struct {
	rows   map[[2]uuid.UUID]collabMemberRow
	writes int
	err    error
	gotCtx context.Context
}

func newFakeCollabMemberStore() *fakeCollabMemberStore {
	return &fakeCollabMemberStore{rows: map[[2]uuid.UUID]collabMemberRow{}}
}

func (f *fakeCollabMemberStore) FindMember(ctx context.Context, collabID, userID uuid.UUID) (usecase.CollabMemberState, bool, error) {
	f.gotCtx = ctx
	if f.err != nil {
		return usecase.CollabMemberState{}, false, f.err
	}
	row, found := f.rows[[2]uuid.UUID{collabID, userID}]
	return usecase.CollabMemberState{Status: row.status, UpdatedAt: row.updated, Version: row.version}, found, nil
}

func (f *fakeCollabMemberStore) InsertMember(_ context.Context, input usecase.ApplyCollabMemberInput) error {
	f.writes++
	f.rows[[2]uuid.UUID{input.CollabID, input.UserID}] = collabMemberRow{input.Status, input.Role, input.RequestedAt, input.OccurredAt, 1}
	return nil
}

func (f *fakeCollabMemberStore) UpdateMember(_ context.Context, input usecase.ApplyCollabMemberInput, expectedVersion int64) error {
	key := [2]uuid.UUID{input.CollabID, input.UserID}
	row := f.rows[key]
	if row.version != expectedVersion {
		return errors.New("version conflict")
	}
	f.writes++
	f.rows[key] = collabMemberRow{input.Status, input.Role, row.requestedAt, input.OccurredAt, row.version + 1}
	return nil
}

// collabMemberHandlerCase is one of the six membership handlers.
type collabMemberHandlerCase struct {
	name      string
	eventType string
	payload   string
	status    string
	// statusField is the payload's status field: status on the created event,
	// collabMemberStatus on the others.
	statusField string
	// requestedAtField is the payload's member creation date field.
	requestedAtField string
	newHandler       func(rabbitmq.CollabMemberTransactor, rabbitmq.CollabMemberIdempotencyRepository, rabbitmq.CollabMemberUsecase, *slog.Logger) func(context.Context, *message.Message) error
}

func collabMemberHandlerCases() []collabMemberHandlerCase {
	return []collabMemberHandlerCase{
		{"CollabJoinRequestCreated", rabbitmq.CollabJoinRequestCreatedEventType, literalCollabJoinRequestCreatedJSON, "PENDING", "status", "createdAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabJoinRequestCreatedHandler(tx, idem, uc, logger).Handle
			}},
		{"CollabJoinRequestAccepted", rabbitmq.CollabJoinRequestAcceptedEventType, literalCollabJoinRequestAcceptedJSON, "ACCEPTED", "collabMemberStatus", "memberCreatedAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabJoinRequestAcceptedHandler(tx, idem, uc, logger).Handle
			}},
		{"CollabJoinRequestDeclined", rabbitmq.CollabJoinRequestDeclinedEventType, literalCollabJoinRequestDeclinedJSON, "REJECTED", "collabMemberStatus", "memberCreatedAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabJoinRequestDeclinedHandler(tx, idem, uc, logger).Handle
			}},
		{"CollabJoinRequestDeleted", rabbitmq.CollabJoinRequestDeletedEventType, literalCollabJoinRequestDeletedJSON, "DELETED", "collabMemberStatus", "memberCreatedAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabJoinRequestDeletedHandler(tx, idem, uc, logger).Handle
			}},
		{"CollabMemberLeft", rabbitmq.CollabMemberLeftEventType, literalCollabMemberLeftJSON, "LEFT", "collabMemberStatus", "createdAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabMemberLeftHandler(tx, idem, uc, logger).Handle
			}},
		{"CollabMemberBanned", rabbitmq.CollabMemberBannedEventType, literalCollabMemberBannedJSON, "BANNED", "collabMemberStatus", "memberCreatedAt",
			func(tx rabbitmq.CollabMemberTransactor, idem rabbitmq.CollabMemberIdempotencyRepository, uc rabbitmq.CollabMemberUsecase, logger *slog.Logger) func(context.Context, *message.Message) error {
				return rabbitmq.NewCollabMemberBannedHandler(tx, idem, uc, logger).Handle
			}},
	}
}

func discardCollabMemberLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func handleMember(t *testing.T, handle func(context.Context, *message.Message) error, payload []byte) error {
	t.Helper()
	return handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
}

func TestCollabMemberHandlers_Handle_ProjectTheLiteralJavaPayloadInsideOneTransaction(t *testing.T) {
	for _, tc := range collabMemberHandlerCases() {
		t.Run(tc.name, func(t *testing.T) {
			idempotency := newFakeCollabMemberIdempotency()
			store := newFakeCollabMemberStore()
			handle := tc.newHandler(fakeCollabMemberTransactor{}, idempotency, usecase.NewApplyCollabMember(store), discardCollabMemberLogger())

			if err := handleMember(t, handle, []byte(tc.payload)); err != nil {
				t.Fatalf("Handle() error = %v, want nil for the exact JSON the Java publisher emits", err)
			}

			row, found := store.rows[[2]uuid.UUID{memberCollabID, memberUserID}]
			if !found || row.status != tc.status || row.role != "MEMBER" {
				t.Fatalf("row = %+v, want %s MEMBER", row, tc.status)
			}
			if !row.requestedAt.Equal(memberRequestedAt) {
				t.Fatalf("requested_at = %v, want the member's creation date %v from %s", row.requestedAt, memberRequestedAt, tc.requestedAtField)
			}
			if idempotency.marks != 1 || store.gotCtx.Value(collabMemberTransactionKey{}) != true || idempotency.markedCtx.Value(collabMemberTransactionKey{}) != true {
				t.Fatalf("marks = %d: the membership write and MarkProcessed must run once, inside the transaction", idempotency.marks)
			}
		})
	}
}

func TestCollabMemberHandlers_Handle_ApplyTransitionsOnlyForwardInTime(t *testing.T) {
	cases := collabMemberHandlerCases()
	created, accepted, declined, banned := cases[0], cases[1], cases[2], cases[5]

	run := func(t *testing.T, steps ...collabMemberHandlerCase) *fakeCollabMemberStore {
		t.Helper()
		store := newFakeCollabMemberStore()
		uc := usecase.NewApplyCollabMember(store)
		for _, step := range steps {
			payload := withField(t, []byte(step.payload), "id", uuid.NewString())
			if err := handleMember(t, step.newHandler(fakeCollabMemberTransactor{}, newFakeCollabMemberIdempotency(), uc, discardCollabMemberLogger()), payload); err != nil {
				t.Fatalf("%s Handle() error = %v", step.name, err)
			}
		}
		return store
	}
	statusOf := func(store *fakeCollabMemberStore) string {
		return store.rows[[2]uuid.UUID{memberCollabID, memberUserID}].status
	}

	if got := statusOf(run(t, created, declined)); got != "REJECTED" {
		t.Fatalf("request then decline = %s, want REJECTED", got)
	}
	// Out of order: the accept is processed first and creates the row from its
	// memberCreatedAt; the later-processed request event is older and must not undo it.
	store := run(t, accepted, created)
	if got := statusOf(store); got != "ACCEPTED" {
		t.Fatalf("accept before its request = %s, want ACCEPTED", got)
	}
	if row := store.rows[[2]uuid.UUID{memberCollabID, memberUserID}]; !row.requestedAt.Equal(memberRequestedAt) || row.version != 1 {
		t.Fatalf("row = %+v, want requested_at from memberCreatedAt and no second write", row)
	}

	later := banned
	later.payload = string(withField(t, []byte(banned.payload), "occurredAt", "2026-03-01T11:00:00Z"))
	if got := statusOf(run(t, created, later, accepted)); got != "BANNED" {
		t.Fatalf("ban, then a late accept = %s, want BANNED", got)
	}
}

func TestCollabMemberHandlers_Handle_FallBackToTheEventTimeWithoutAMemberCreationDate(t *testing.T) {
	for _, tc := range collabMemberHandlerCases()[1:] {
		t.Run(tc.name, func(t *testing.T) {
			store := newFakeCollabMemberStore()
			handle := tc.newHandler(fakeCollabMemberTransactor{}, newFakeCollabMemberIdempotency(), usecase.NewApplyCollabMember(store), discardCollabMemberLogger())

			for _, value := range []any{nil, "null"} {
				payload := withField(t, []byte(tc.payload), tc.requestedAtField, nil)
				if value == "null" {
					payload = bytes.Replace([]byte(tc.payload), []byte(`"`+tc.requestedAtField+`":"2026-03-01T10:15:00.5Z"`), []byte(`"`+tc.requestedAtField+`":null`), 1)
				}
				store.rows = map[[2]uuid.UUID]collabMemberRow{}
				if err := handleMember(t, handle, withField(t, payload, "id", uuid.NewString())); err != nil {
					t.Fatalf("Handle() error = %v", err)
				}
				want := time.Date(2026, 3, 1, 10, 20, 0, 123000000, time.UTC)
				if got := store.rows[[2]uuid.UUID{memberCollabID, memberUserID}].requestedAt; !got.Equal(want) {
					t.Fatalf("requested_at = %v, want the event time %v", got, want)
				}
			}
		})
	}
}

func TestCollabMemberHandlers_Handle_SkipADuplicateAndAck(t *testing.T) {
	for _, tc := range collabMemberHandlerCases() {
		t.Run(tc.name, func(t *testing.T) {
			idempotency := newFakeCollabMemberIdempotency()
			idempotency.processed[uuid.MustParse(loggedEventID)] = true
			store := newFakeCollabMemberStore()
			handle := tc.newHandler(fakeCollabMemberTransactor{}, idempotency, usecase.NewApplyCollabMember(store), discardCollabMemberLogger())

			if err := handleMember(t, handle, []byte(tc.payload)); err != nil {
				t.Fatalf("Handle() error = %v, want nil (duplicate events ack cleanly)", err)
			}
			if store.writes != 0 || idempotency.marks != 0 {
				t.Fatalf("writes = %d, marks = %d, want none for a duplicate", store.writes, idempotency.marks)
			}
		})
	}
}

func TestCollabMemberHandlers_Handle_ReturnAPermanentErrorForAnInvalidPayload(t *testing.T) {
	for _, tc := range collabMemberHandlerCases() {
		t.Run(tc.name, func(t *testing.T) {
			payload := []byte(tc.payload)
			cases := map[string][]byte{
				"not json":        []byte(`not json`),
				"snake_case":      []byte(`{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:15:30Z","collab_id":"66666666-6666-4666-8666-666666666666","user_id":"44444444-4444-4444-8444-444444444444","status":"PENDING","role":"MEMBER"}`),
				"bad collabId":    withField(t, payload, "collabId", "not-a-uuid"),
				"nil userId":      withField(t, payload, "userId", uuid.Nil.String()),
				"other status":    withField(t, payload, tc.statusField, "INVITED"),
				"lowercase role":  withField(t, payload, "role", "member"),
				"unknown role":    withField(t, payload, "role", "OWNER"),
				"bad date":        withField(t, payload, "occurredAt", "yesterday"),
				"status of other": withField(t, payload, tc.statusField, otherStatus(tc.status)),
			}
			for _, field := range []string{"id", "correlationId", "occurredAt", "collabId", "userId", tc.statusField, "role"} {
				cases["without "+field] = withField(t, payload, field, nil)
			}
			if tc.name == "CollabJoinRequestCreated" {
				cases["without createdAt"] = withField(t, payload, "createdAt", nil)
			}

			for name, invalid := range cases {
				t.Run(name, func(t *testing.T) {
					idempotency := newFakeCollabMemberIdempotency()
					store := newFakeCollabMemberStore()
					handle := tc.newHandler(fakeCollabMemberTransactor{}, idempotency, usecase.NewApplyCollabMember(store), discardCollabMemberLogger())

					if err := handleMember(t, handle, invalid); !rmqerror.IsPermanent(err) {
						t.Fatalf("Handle() error = %v, want a permanent error so it skips retries", err)
					}
					if store.writes != 0 || idempotency.marks != 0 {
						t.Fatalf("writes = %d, marks = %d, want none", store.writes, idempotency.marks)
					}
				})
			}
		})
	}
}

// otherStatus is a valid member status the routing key does not promise.
func otherStatus(status string) string {
	if status == "PENDING" {
		return "ACCEPTED"
	}
	return "PENDING"
}

func TestCollabMemberHandlers_Handle_ReturnATransientErrorWhenTheProjectionFails(t *testing.T) {
	for _, tc := range collabMemberHandlerCases() {
		t.Run(tc.name, func(t *testing.T) {
			idempotency := newFakeCollabMemberIdempotency()
			store := newFakeCollabMemberStore()
			store.err = errors.New("mongo unavailable")
			handle := tc.newHandler(fakeCollabMemberTransactor{}, idempotency, usecase.NewApplyCollabMember(store), discardCollabMemberLogger())

			err := handleMember(t, handle, []byte(tc.payload))
			if err == nil || rmqerror.IsPermanent(err) || !errors.Is(err, store.err) {
				t.Fatalf("Handle() error = %v, want the transient failure so it is retried", err)
			}
			if idempotency.marks != 0 {
				t.Fatalf("marks = %d, want none when the projection failed", idempotency.marks)
			}
		})
	}
}

func TestCollabMemberHandlers_Handle_LogAStaleEventWithoutCallingItProjected(t *testing.T) {
	cases := collabMemberHandlerCases()
	created, accepted := cases[0], cases[1]
	store := newFakeCollabMemberStore()
	uc := usecase.NewApplyCollabMember(store)
	if err := handleMember(t, accepted.newHandler(fakeCollabMemberTransactor{}, newFakeCollabMemberIdempotency(), uc, discardCollabMemberLogger()), []byte(accepted.payload)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	logs := &bytes.Buffer{}
	idempotency := newFakeCollabMemberIdempotency()
	handle := created.newHandler(fakeCollabMemberTransactor{}, idempotency, uc, slog.New(slog.NewTextHandler(logs, nil)))
	if err := handleMember(t, handle, withField(t, []byte(created.payload), "id", uuid.NewString())); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	assertLogged(t, logs, "level=INFO", "ignoring stale collab join request created event", "correlation_id="+loggedCorrelationID, "userId="+loggedUserID)
	if strings.Contains(logs.String(), "event projected") {
		t.Fatalf("a stale event logged as projected: %s", logs)
	}
	if idempotency.marks != 1 {
		t.Fatalf("marks = %d, want the stale event recorded as processed", idempotency.marks)
	}
}

func TestCollabMemberHandlers_Handle_RequireTheirDependencies(t *testing.T) {
	handle := rabbitmq.NewCollabMemberBannedHandler(nil, newFakeCollabMemberIdempotency(), usecase.NewApplyCollabMember(newFakeCollabMemberStore()), discardCollabMemberLogger()).Handle
	if err := handleMember(t, handle, []byte(literalCollabMemberBannedJSON)); err == nil {
		t.Fatal("Handle() without a transactor error = nil, want an error")
	}
}
