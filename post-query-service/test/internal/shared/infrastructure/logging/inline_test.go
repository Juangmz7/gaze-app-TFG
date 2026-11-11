package logging_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"log/slog"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/logging"
)

func newLogger(buffer *bytes.Buffer) *slog.Logger {
	return slog.New(logging.NewInlineAttributesHandler(slog.NewJSONHandler(buffer, nil)))
}

func decode(t *testing.T, buffer *bytes.Buffer) map[string]any {
	t.Helper()
	var line map[string]any
	if err := json.Unmarshal(buffer.Bytes(), &line); err != nil {
		t.Fatalf("decode log line %q: %v", buffer, err)
	}
	return line
}

// Grafana lists a log by its message: the ids must be readable there, and still be
// attributes to filter on.
func TestInlineAttributesHandler_PutsTheAttributesInTheMessageAndKeepsThem(t *testing.T) {
	buffer := &bytes.Buffer{}
	newLogger(buffer).Info("feed disconnect recorded", "userId", "u-1", "correlation_id", "c-1", "items", 3)

	line := decode(t, buffer)
	if want := "feed disconnect recorded userId=u-1 correlation_id=c-1 items=3"; line["msg"] != want {
		t.Fatalf("msg = %q, want %q", line["msg"], want)
	}
	if line["userId"] != "u-1" || line["correlation_id"] != "c-1" {
		t.Fatalf("attributes lost: %v", line)
	}
}

func TestInlineAttributesHandler_IncludesWithAttributesAndGroups(t *testing.T) {
	buffer := &bytes.Buffer{}
	newLogger(buffer).With("routing_key", "rk.post.created").WithGroup("retry").Warn("event failed", "attempt", 2)

	if want := "event failed routing_key=rk.post.created retry.attempt=2"; decode(t, buffer)["msg"] != want {
		t.Fatalf("msg = %q, want %q", decode(t, buffer)["msg"], want)
	}
}

func TestInlineAttributesHandler_QuotesValuesThatAreNotOneToken(t *testing.T) {
	buffer := &bytes.Buffer{}
	newLogger(buffer).Error("outbox publish failed", "error", errors.New("dial tcp: i/o timeout"), "reason", "")

	if want := `outbox publish failed error="dial tcp: i/o timeout" reason=""`; decode(t, buffer)["msg"] != want {
		t.Fatalf("msg = %q, want %q", decode(t, buffer)["msg"], want)
	}
}

func TestInlineAttributesHandler_LeavesAMessageWithoutAttributesAsItIs(t *testing.T) {
	buffer := &bytes.Buffer{}
	newLogger(buffer).Info("consuming rabbitmq events")

	if decode(t, buffer)["msg"] != "consuming rabbitmq events" {
		t.Fatalf("msg = %q", decode(t, buffer)["msg"])
	}
}
