package rabbitmq_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/like/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

const (
	loggedEventID       = "11111111-1111-4111-8111-111111111111"
	loggedCorrelationID = "22222222-2222-4222-8222-222222222222"
	loggedUserID        = "44444444-4444-4444-8444-444444444444"
)

// loggedHandlerCase is one handler whose logs are checked. duplicate makes the event
// count as already processed.
type loggedHandlerCase struct {
	name      string
	eventType string
	projected string
	payload   func(t *testing.T) []byte
	handle    func(logger *slog.Logger, duplicate bool) func(context.Context, *message.Message) error
}

// withField returns a copy of the literal payload with key set to value, or removed
// when value is nil.
func withField(t *testing.T, payload []byte, key string, value any) []byte {
	t.Helper()

	var fields map[string]any
	if err := json.Unmarshal(payload, &fields); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if value == nil {
		delete(fields, key)
	} else {
		fields[key] = value
	}
	changed, err := json.Marshal(fields)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	return changed
}

func assertLogged(t *testing.T, logs *bytes.Buffer, wants ...string) {
	t.Helper()

	for _, want := range wants {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log misses %q: %s", want, logs)
		}
	}
}

func TestEventHandlers_LogTheCorrelationAndUserOfEveryOutcome(t *testing.T) {
	identity := []string{"correlation_id=" + loggedCorrelationID, "userId=" + loggedUserID}

	for _, tc := range loggedHandlerCases() {
		t.Run(tc.name, func(t *testing.T) {
			handle := func(duplicate bool, payload []byte) (*bytes.Buffer, error) {
				logs := &bytes.Buffer{}
				logger := slog.New(slog.NewTextHandler(logs, nil))
				err := tc.handle(logger, duplicate)(context.Background(), message.NewMessage(uuid.NewString(), payload))
				return logs, err
			}

			t.Run("projected", func(t *testing.T) {
				logs, err := handle(false, tc.payload(t))
				if err != nil {
					t.Fatalf("Handle() error = %v", err)
				}
				assertLogged(t, logs, append(identity, "level=INFO", tc.projected, "event_id="+loggedEventID)...)
			})

			t.Run("duplicate", func(t *testing.T) {
				logs, err := handle(true, tc.payload(t))
				if err != nil {
					t.Fatalf("Handle() error = %v", err)
				}
				assertLogged(t, logs, append(identity, "level=WARN", "discarding duplicate", "event_id="+loggedEventID)...)
				if strings.Contains(logs.String(), tc.projected) {
					t.Fatalf("a duplicate logged as projected: %s", logs)
				}
			})

			invalid := map[string]func(t *testing.T) []byte{
				"fails validation": func(t *testing.T) []byte { return withField(t, tc.payload(t), "occurredAt", nil) },
				"does not decode":  func(t *testing.T) []byte { return withField(t, tc.payload(t), "occurredAt", "not-a-date") },
			}
			for name, payload := range invalid {
				t.Run(name, func(t *testing.T) {
					logs, err := handle(false, payload(t))
					if !rmqerror.IsPermanent(err) {
						t.Fatalf("Handle() error = %v, want a permanent error", err)
					}
					assertLogged(t, logs, append(identity, "level=WARN", "discarding invalid event", "event_type="+tc.eventType, "event_id="+loggedEventID)...)
				})
			}
		})
	}
}

func loggedHandlerCases() []loggedHandlerCase {
	return []loggedHandlerCase{
		{
			name:      "PostLikeCreated",
			eventType: rabbitmq.PostLikeCreatedEventType,
			projected: "post like created event projected",
			payload:   func(*testing.T) []byte { return []byte(literalPostLikeCreatedJSON) },
			handle: func(logger *slog.Logger, duplicate bool) func(context.Context, *message.Message) error {
				idempotency := newFakePostLikeCreatedIdempotencyRepository()
				idempotency.processed[uuid.MustParse(loggedEventID)] = duplicate
				return rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, idempotency, &fakePostLikeCreatedUsecase{}, logger).Handle
			},
		},
		{
			name:      "PostLikeDeleted",
			eventType: rabbitmq.PostLikeDeletedEventType,
			projected: "post like deleted event projected",
			payload: func(t *testing.T) []byte {
				return literalPostLikeDeletedEvent(t, map[string]any{"id": loggedEventID, "correlationId": loggedCorrelationID, "userId": loggedUserID})
			},
			handle: func(logger *slog.Logger, duplicate bool) func(context.Context, *message.Message) error {
				idempotency := newFakePostLikeDeletedIdempotencyRepository()
				idempotency.processed[uuid.MustParse(loggedEventID)] = duplicate
				return rabbitmq.NewPostLikeDeletedHandler(passthroughPostLikeDeletedTransactor{}, idempotency, &fakePostLikeDeletedUsecase{}, logger).Handle
			},
		},
	}
}

// twiceTransactor runs its function twice, the way the driver re-runs a transaction on
// a transient error, and keeps the second result.
type twiceTransactor struct{}

func (twiceTransactor) WithTransaction(ctx context.Context, fn func(ctx context.Context) error) error {
	if err := fn(ctx); err != nil {
		return err
	}
	return fn(ctx)
}

func TestPostLikeCreatedHandler_Handle_LogsNothingAsProjectedWhenTheTransactionFails(t *testing.T) {
	logs := &bytes.Buffer{}
	uc := &fakePostLikeCreatedUsecase{execErr: errors.New("post not projected")}
	handler := rabbitmq.NewPostLikeCreatedHandler(fakePostLikeCreatedTransactor{}, newFakePostLikeCreatedIdempotencyRepository(), uc, slog.New(slog.NewTextHandler(logs, nil)))

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err == nil {
		t.Fatal("Handle() error = nil, want the usecase's error")
	}
	if strings.Contains(logs.String(), "event projected") {
		t.Fatalf("a failed transaction logged as projected: %s", logs)
	}
}

func TestPostLikeCreatedHandler_Handle_LogsOneProjectionWhenTheDriverReRunsTheTransaction(t *testing.T) {
	logs := &bytes.Buffer{}
	handler := rabbitmq.NewPostLikeCreatedHandler(twiceTransactor{}, newFakePostLikeCreatedIdempotencyRepository(), &fakePostLikeCreatedUsecase{}, slog.New(slog.NewTextHandler(logs, nil)))

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), []byte(literalPostLikeCreatedJSON))); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if got := strings.Count(logs.String(), "event projected"); got != 1 {
		t.Fatalf("projected lines = %d, want 1: %s", got, logs)
	}
}

func TestPostLikeDeletedHandler_Handle_PassesTheCorrelationIDToTheUsecase(t *testing.T) {
	uc := &fakePostLikeDeletedUsecase{}
	handler := rabbitmq.NewPostLikeDeletedHandler(passthroughPostLikeDeletedTransactor{}, newFakePostLikeDeletedIdempotencyRepository(), uc, slog.New(slog.DiscardHandler))
	payload := literalPostLikeDeletedEvent(t, map[string]any{"correlationId": loggedCorrelationID, "userId": loggedUserID})

	if err := handler.Handle(context.Background(), message.NewMessage(uuid.NewString(), payload)); err != nil {
		t.Fatalf("Handle() error = %v", err)
	}
	if uc.gotIn.CorrelationID != uuid.MustParse(loggedCorrelationID) || uc.gotIn.UserID != uuid.MustParse(loggedUserID) {
		t.Fatalf("input = %+v, want the event's correlation id and user", uc.gotIn)
	}
}
