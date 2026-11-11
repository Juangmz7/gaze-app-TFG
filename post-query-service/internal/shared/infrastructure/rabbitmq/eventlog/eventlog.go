// Package eventlog says which event a log line is about: an event the handlers
// discard because it is invalid, and any line about a message in flight.
package eventlog

import (
	"context"
	"encoding/json"
	"log/slog"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

// eventIdentity is what a log line needs from a payload. Its fields are plain
// strings, unlike the handlers' typed events, because this reads payloads those events
// may fail to decode: a bad value in one field must not hide the others.
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
	identity, err := identityAttributes(payload)
	// A decode failure here only means fewer fields to log: cause already explains
	// what is wrong with the payload.
	if err != nil {
		logger.DebugContext(ctx, "invalid event payload is only partly readable", "event_type", eventType, "error", err)
	}

	logger.WarnContext(ctx, "discarding invalid event", append([]any{"event_type", eventType, "error", cause}, identity...)...)
}

// IdentityAttributes returns event_id, correlation_id and userId from payload, the
// ones that hold a valid UUID. An unreadable payload returns what could be read.
func IdentityAttributes(payload []byte) []any {
	identity, _ := identityAttributes(payload)
	return identity
}

// MessageAttributes names the message: its routing key, which says the event type, its
// message id, and IdentityAttributes of its payload.
func MessageAttributes(msg *message.Message) []any {
	attributes := []any{"routing_key", msg.Metadata.Get(topology.RoutingKeyHeader), "message_uuid", msg.UUID}
	return append(attributes, IdentityAttributes(msg.Payload)...)
}

func identityAttributes(payload []byte) ([]any, error) {
	var identity eventIdentity
	err := json.Unmarshal(payload, &identity)

	attributes := appendValidUUID(nil, "event_id", identity.ID)
	attributes = appendValidUUID(attributes, "correlation_id", identity.CorrelationID)
	if identity.UserID != "" {
		attributes = appendValidUUID(attributes, "userId", identity.UserID)
	} else {
		attributes = appendValidUUID(attributes, "userId", identity.CreatedBy)
	}

	return attributes, err
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
