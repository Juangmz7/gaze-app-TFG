package rabbitmq_test

import (
	"bytes"
	"context"
	"errors"
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

// The exact JSON post-command-service's CollabJoinInstructionsUpdatedEvent record
// produces (Jackson, camelCase, Instants as ISO-8601, nulls written). Literals, never a
// marshalled handler struct.
const (
	literalCollabJoinInstructionsUpdatedJSON        = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:30:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","joinInstructions":"Repo: https://git.example.test/harbour\nChat: https://chat.example.test/invite/harbour"}`
	literalCollabJoinInstructionsClearedJSON        = `{"id":"11111111-1111-4111-8111-111111111111","correlationId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-03-01T10:30:00.123456Z","collabId":"66666666-6666-4666-8666-666666666666","joinInstructions":null}`
	literalCollabJoinInstructionsUpdatedSnakeJSON   = `{"id":"11111111-1111-4111-8111-111111111111","correlation_id":"22222222-2222-4222-8222-222222222222","occurred_at":"2026-03-01T10:30:00.123456Z","collab_id":"66666666-6666-4666-8666-666666666666","join_instructions":null}`
	literalCollabJoinInstructionsUpdatedCollabID    = "66666666-6666-4666-8666-666666666666"
	literalCollabJoinInstructionsUpdatedEventID     = "11111111-1111-4111-8111-111111111111"
	literalCollabJoinInstructionsUpdatedCorrelation = "22222222-2222-4222-8222-222222222222"
)

type fakeJoinInstructionsIdempotency struct {
	processed  map[uuid.UUID]bool
	markCalls  int
	markedType string
	checkErr   error
	markErr    error
}

func newFakeJoinInstructionsIdempotency() *fakeJoinInstructionsIdempotency {
	return &fakeJoinInstructionsIdempotency{processed: map[uuid.UUID]bool{}}
}

func (f *fakeJoinInstructionsIdempotency) IsProcessed(_ context.Context, eventID uuid.UUID) (bool, error) {
	return f.processed[eventID], f.checkErr
}

func (f *fakeJoinInstructionsIdempotency) MarkProcessed(_ context.Context, eventID, _ uuid.UUID, eventType string) error {
	if f.markErr != nil {
		return f.markErr
	}
	f.markCalls++
	f.markedType = eventType
	f.processed[eventID] = true
	return nil
}

type fakeJoinInstructionsUsecase struct {
	calls   int
	gotIn   usecase.UpdateCollabJoinInstructionsInput
	applied bool
	err     error
}

func (f *fakeJoinInstructionsUsecase) Execute(_ context.Context, input usecase.UpdateCollabJoinInstructionsInput) (bool, error) {
	f.calls++
	f.gotIn = input
	return f.applied, f.err
}

func newJoinInstructionsHandler(idempotency *fakeJoinInstructionsIdempotency, uc *fakeJoinInstructionsUsecase, logs *bytes.Buffer) *rabbitmq.CollabJoinInstructionsUpdatedHandler {
	return rabbitmq.NewCollabJoinInstructionsUpdatedHandler(idempotency, uc, slog.New(slog.NewTextHandler(logs, nil)))
}

func handleJoinInstructions(handler *rabbitmq.CollabJoinInstructionsUpdatedHandler, payload []byte) error {
	return handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload))
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_ProjectsTheLiteralJavaPayload(t *testing.T) {
	tests := []struct {
		name    string
		payload string
		want    string
	}{
		{name: "new text", payload: literalCollabJoinInstructionsUpdatedJSON, want: "Repo: https://git.example.test/harbour\nChat: https://chat.example.test/invite/harbour"},
		{name: "cleared (null)", payload: literalCollabJoinInstructionsClearedJSON, want: ""},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			idempotency := newFakeJoinInstructionsIdempotency()
			uc := &fakeJoinInstructionsUsecase{applied: true}
			logs := &bytes.Buffer{}

			if err := handleJoinInstructions(newJoinInstructionsHandler(idempotency, uc, logs), []byte(tt.payload)); err != nil {
				t.Fatalf("Handle() error = %v", err)
			}

			if uc.calls != 1 || uc.gotIn.CollabID != uuid.MustParse(literalCollabJoinInstructionsUpdatedCollabID) || uc.gotIn.JoinInstructions != tt.want {
				t.Fatalf("Execute() calls = %d, input = %+v, want one call for collab %s with %q", uc.calls, uc.gotIn, literalCollabJoinInstructionsUpdatedCollabID, tt.want)
			}
			if want := time.Date(2026, 3, 1, 10, 30, 0, 123456000, time.UTC); !uc.gotIn.OccurredAt.Equal(want) {
				t.Fatalf("Execute() OccurredAt = %v, want %v", uc.gotIn.OccurredAt, want)
			}
			if idempotency.markCalls != 1 || idempotency.markedType != rabbitmq.CollabJoinInstructionsUpdatedEventType {
				t.Fatalf("MarkProcessed() calls = %d, type = %q, want one mark as %q", idempotency.markCalls, idempotency.markedType, rabbitmq.CollabJoinInstructionsUpdatedEventType)
			}
			assertLogged(t, logs, "level=INFO", "collab join instructions updated event projected",
				"event_id="+literalCollabJoinInstructionsUpdatedEventID, "correlation_id="+literalCollabJoinInstructionsUpdatedCorrelation, "collab_id="+literalCollabJoinInstructionsUpdatedCollabID)
		})
	}
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_TreatsAnAbsentFieldAsCleared(t *testing.T) {
	uc := &fakeJoinInstructionsUsecase{applied: true}
	payload := withField(t, []byte(literalCollabJoinInstructionsUpdatedJSON), "joinInstructions", nil)

	if err := handleJoinInstructions(newJoinInstructionsHandler(newFakeJoinInstructionsIdempotency(), uc, &bytes.Buffer{}), payload); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if uc.gotIn.JoinInstructions != "" {
		t.Fatalf("Execute() JoinInstructions = %q, want empty", uc.gotIn.JoinInstructions)
	}
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_MarksAStaleEventProcessedAndLogsItAsStale(t *testing.T) {
	idempotency := newFakeJoinInstructionsIdempotency()
	logs := &bytes.Buffer{}

	if err := handleJoinInstructions(newJoinInstructionsHandler(idempotency, &fakeJoinInstructionsUsecase{applied: false}, logs), []byte(literalCollabJoinInstructionsUpdatedJSON)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}

	if idempotency.markCalls != 1 {
		t.Fatalf("MarkProcessed() calls = %d, want 1: a stale event is still processed", idempotency.markCalls)
	}
	assertLogged(t, logs, "stale collab join instructions updated event skipped", "event_id="+literalCollabJoinInstructionsUpdatedEventID)
	if strings.Contains(logs.String(), "event projected") {
		t.Fatalf("a stale event logged as projected: %s", logs)
	}
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_AcksADuplicateWithoutCallingTheUsecase(t *testing.T) {
	idempotency := newFakeJoinInstructionsIdempotency()
	idempotency.processed[uuid.MustParse(literalCollabJoinInstructionsUpdatedEventID)] = true
	uc := &fakeJoinInstructionsUsecase{applied: true}
	logs := &bytes.Buffer{}
	handler := newJoinInstructionsHandler(idempotency, uc, logs)

	for range 2 {
		if err := handleJoinInstructions(handler, []byte(literalCollabJoinInstructionsUpdatedJSON)); err != nil {
			t.Fatalf("Handle() error = %v, want nil: a duplicate is acked", err)
		}
	}

	if uc.calls != 0 || idempotency.markCalls != 0 {
		t.Fatalf("Execute() calls = %d, MarkProcessed() calls = %d, want 0 and 0 for a duplicate", uc.calls, idempotency.markCalls)
	}
	assertLogged(t, logs, "level=WARN", "discarding duplicate collab join instructions updated event", "event_id="+literalCollabJoinInstructionsUpdatedEventID)
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_RejectsAMalformedPayloadAsPermanent(t *testing.T) {
	literal := []byte(literalCollabJoinInstructionsUpdatedJSON)
	tests := map[string]func(t *testing.T) []byte{
		"not JSON":                    func(*testing.T) []byte { return []byte(`not json`) },
		"snake_case":                  func(*testing.T) []byte { return []byte(literalCollabJoinInstructionsUpdatedSnakeJSON) },
		"missing id":                  func(t *testing.T) []byte { return withField(t, literal, "id", nil) },
		"missing correlationId":       func(t *testing.T) []byte { return withField(t, literal, "correlationId", nil) },
		"missing occurredAt":          func(t *testing.T) []byte { return withField(t, literal, "occurredAt", nil) },
		"missing collabId":            func(t *testing.T) []byte { return withField(t, literal, "collabId", nil) },
		"invalid collabId":            func(t *testing.T) []byte { return withField(t, literal, "collabId", "not-a-uuid") },
		"invalid occurredAt":          func(t *testing.T) []byte { return withField(t, literal, "occurredAt", "yesterday") },
		"non-string joinInstructions": func(t *testing.T) []byte { return withField(t, literal, "joinInstructions", 42) },
	}

	for name, payload := range tests {
		t.Run(name, func(t *testing.T) {
			idempotency := newFakeJoinInstructionsIdempotency()
			uc := &fakeJoinInstructionsUsecase{applied: true}
			logs := &bytes.Buffer{}

			err := handleJoinInstructions(newJoinInstructionsHandler(idempotency, uc, logs), payload(t))
			if !rmqerror.IsPermanent(err) {
				t.Fatalf("Handle() error = %v, want a permanent error", err)
			}
			if uc.calls != 0 || idempotency.markCalls != 0 {
				t.Fatalf("Execute() calls = %d, MarkProcessed() calls = %d, want 0 and 0", uc.calls, idempotency.markCalls)
			}
			assertLogged(t, logs, "level=WARN", "discarding invalid event", "event_type="+rabbitmq.CollabJoinInstructionsUpdatedEventType)
		})
	}
}

func TestCollabJoinInstructionsUpdatedHandler_Handle_RetriesDependencyFailuresWithoutMarking(t *testing.T) {
	failure := errors.New("mongo unavailable")
	tests := map[string]struct {
		idempotency *fakeJoinInstructionsIdempotency
		uc          *fakeJoinInstructionsUsecase
		wantMarks   int
	}{
		"idempotency check fails": {idempotency: &fakeJoinInstructionsIdempotency{processed: map[uuid.UUID]bool{}, checkErr: failure}, uc: &fakeJoinInstructionsUsecase{applied: true}},
		"usecase fails":           {idempotency: newFakeJoinInstructionsIdempotency(), uc: &fakeJoinInstructionsUsecase{err: failure}},
		"mark fails":              {idempotency: &fakeJoinInstructionsIdempotency{processed: map[uuid.UUID]bool{}, markErr: failure}, uc: &fakeJoinInstructionsUsecase{applied: true}},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			err := handleJoinInstructions(newJoinInstructionsHandler(tt.idempotency, tt.uc, &bytes.Buffer{}), []byte(literalCollabJoinInstructionsUpdatedJSON))
			if err == nil || rmqerror.IsPermanent(err) || !errors.Is(err, failure) {
				t.Fatalf("Handle() error = %v, want a retryable error wrapping %v", err, failure)
			}
			if tt.idempotency.markCalls != tt.wantMarks {
				t.Fatalf("MarkProcessed() calls = %d, want %d", tt.idempotency.markCalls, tt.wantMarks)
			}
		})
	}
}
