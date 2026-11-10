package eventlog_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/eventlog"
)

const (
	eventID       = "11111111-1111-4111-8111-111111111111"
	correlationID = "22222222-2222-4222-8222-222222222222"
	userID        = "44444444-4444-4444-8444-444444444444"
)

func TestWarnInvalid_LogsWhatThePayloadProvides(t *testing.T) {
	cases := map[string]struct {
		payload string
		want    []string
		absent  []string
	}{
		"every field": {
			payload: `{"id":"` + eventID + `","correlationId":"` + correlationID + `","userId":"` + userID + `"}`,
			want:    []string{"event_id=" + eventID, "correlation_id=" + correlationID, "userId=" + userID},
		},
		"a collab event names its user createdBy": {
			payload: `{"correlationId":"` + correlationID + `","createdBy":"` + userID + `"}`,
			want:    []string{"correlation_id=" + correlationID, "userId=" + userID},
			absent:  []string{"event_id="},
		},
		"a value of the wrong type hides only itself": {
			payload: `{"id":7,"correlationId":"` + correlationID + `","userId":"` + userID + `"}`,
			want:    []string{"correlation_id=" + correlationID, "userId=" + userID},
			absent:  []string{"event_id="},
		},
		"a value that is not a UUID stays out of the logs": {
			payload: `{"correlationId":"<script>","userId":"` + userID + `"}`,
			want:    []string{"userId=" + userID},
			absent:  []string{"correlation_id=", "script"},
		},
		"a payload that is not JSON": {
			payload: `not json`,
			absent:  []string{"event_id=", "correlation_id=", "userId="},
		},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			logs := &bytes.Buffer{}
			logger := slog.New(slog.NewTextHandler(logs, nil))

			eventlog.WarnInvalid(context.Background(), logger, "SomeEvent", []byte(tc.payload), errors.New("occurredAt is required"))

			for _, want := range append(tc.want, "level=WARN", "discarding invalid event", "event_type=SomeEvent", "occurredAt is required") {
				if !strings.Contains(logs.String(), want) {
					t.Fatalf("log misses %q: %s", want, logs)
				}
			}
			for _, absent := range tc.absent {
				if strings.Contains(logs.String(), absent) {
					t.Fatalf("log holds %q: %s", absent, logs)
				}
			}
		})
	}
}
