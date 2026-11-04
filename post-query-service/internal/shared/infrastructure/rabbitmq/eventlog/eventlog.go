// Package eventlog logs an event the handlers discard because it is invalid.
package eventlog

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/google/uuid"
)

// eventIdentity is what a discard log needs from a payload. Its fields are plain
// strings, unlike the handlers' typed events, because this reads payloads those events
// failed to decode: a bad value in one field must not hide the others.
type eventIdentity struct {
	ID            string `json:"id"`
	CorrelationID string `json:"correlationId"`
	UserID        string `json:"userId"`
	CreatedBy     string `json:"createdBy"`
}

// WarnInvalid logs at warning level that an event of eventType is being discarded
// because of cause. It carries event_id, correlation_id and userId when the payload
// holds a valid UUID for them, and leaves out the ones it does not. A collab event
// names its user createdBy.
func WarnInvalid(ctx context.Context, logger *slog.Logger, eventType string, payload []byte, cause error) {
	var identity eventIdentity
	// A decode failure here only means fewer fields to log: cause already explains
	// what is wrong with the payload.
	if err := json.Unmarshal(payload, &identity); err != nil {
		logger.DebugContext(ctx, "invalid event payload is only partly readable", "event_type", eventType, "error", err)
	}

	attributes := []any{"event_type", eventType, "error", cause}
	attributes = appendValidUUID(attributes, "event_id", identity.ID)
	attributes = appendValidUUID(attributes, "correlation_id", identity.CorrelationID)
	if identity.UserID != "" {
		attributes = appendValidUUID(attributes, "userId", identity.UserID)
	} else {
		attributes = appendValidUUID(attributes, "userId", identity.CreatedBy)
	}

	logger.WarnContext(ctx, "discarding invalid event", attributes...)
}

// appendValidUUID adds the attribute only for a valid UUID. The payload is untrusted,
// and this keeps its values out of the logs and their length bounded.
func appendValidUUID(attributes []any, key, value string) []any {
	id, err := uuid.Parse(value)
	if err != nil {
		return attributes
	}

	return append(attributes, key, id)
}
