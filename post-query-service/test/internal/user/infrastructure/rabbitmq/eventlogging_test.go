package rabbitmq_test

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"testing"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/user/infrastructure/rabbitmq"
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
			name:      "UserRegistered",
			eventType: rabbitmq.UserRegisteredEventType,
			projected: "user registered event projected",
			payload:   func(*testing.T) []byte { return []byte(literalUserRegisteredJSON) },
			handle: func(logger *slog.Logger, duplicate bool) func(context.Context, *message.Message) error {
				idempotency := newFakeUserRegisteredIdempotencyRepository()
				idempotency.processed[uuid.MustParse(loggedEventID)] = duplicate
				return rabbitmq.NewUserRegisteredHandler(idempotency, &fakeUserRegisteredUsecase{}, logger).Handle
			},
		},
		{
			name:      "UserDeleted",
			eventType: rabbitmq.UserDeletedEventType,
			projected: "user deleted event projected",
			payload:   func(*testing.T) []byte { return []byte(literalUserDeletedJSON) },
			handle: func(logger *slog.Logger, duplicate bool) func(context.Context, *message.Message) error {
				idempotency := newFakeUserDeletedIdempotencyRepository()
				idempotency.processed[uuid.MustParse(loggedEventID)] = duplicate
				return rabbitmq.NewUserDeletedHandler(idempotency, &fakeUserDeletedUsecase{}, logger).Handle
			},
		},
	}
}
