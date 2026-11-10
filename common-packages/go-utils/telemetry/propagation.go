package telemetry

import (
	"context"
	"strings"

	"github.com/ThreeDotsLabs/watermill/message"
	amqp "github.com/rabbitmq/amqp091-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

// SetupPropagator registers the global W3C TraceContext + Baggage
// propagator. Init calls it; call it directly only in processes that use the
// Inject/Extract helpers without calling Init (e.g. tests).
func SetupPropagator() {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
}

// AMQPHeadersCarrier adapts RabbitMQ message headers to
// propagation.TextMapCarrier.
type AMQPHeadersCarrier amqp.Table

var _ propagation.TextMapCarrier = AMQPHeadersCarrier(nil)

// Get returns the header value for key. Header names are matched exactly
// first and then case-insensitively; string and []byte values are supported
// (other AMQP clients may send either).
func (c AMQPHeadersCarrier) Get(key string) string {
	value, ok := c[key]
	if !ok {
		for k, v := range c {
			if strings.EqualFold(k, key) {
				value, ok = v, true
				break
			}
		}
	}
	if !ok {
		return ""
	}
	switch v := value.(type) {
	case string:
		return v
	case []byte:
		return string(v)
	default:
		return ""
	}
}

// Set stores value as a string header. The underlying table must be non-nil.
func (c AMQPHeadersCarrier) Set(key, value string) {
	c[key] = value
}

func (c AMQPHeadersCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for k := range c {
		keys = append(keys, k)
	}
	return keys
}

// InjectAMQPHeaders writes the trace context and baggage from ctx into
// headers and returns them; a nil table is allocated. Use as:
//
//	pub.Headers = telemetry.InjectAMQPHeaders(ctx, pub.Headers)
func InjectAMQPHeaders(ctx context.Context, headers amqp.Table) amqp.Table {
	if headers == nil {
		headers = amqp.Table{}
	}
	otel.GetTextMapPropagator().Inject(ctx, AMQPHeadersCarrier(headers))
	return headers
}

// ExtractAMQPHeaders returns ctx enriched with the remote span context and
// baggage found in headers (ctx unchanged if there are none).
func ExtractAMQPHeaders(ctx context.Context, headers amqp.Table) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, AMQPHeadersCarrier(headers))
}

// MetadataCarrier adapts Watermill message metadata to
// propagation.TextMapCarrier.
type MetadataCarrier message.Metadata

var _ propagation.TextMapCarrier = MetadataCarrier(nil)

// Get returns the metadata value for key, matched exactly first and then
// case-insensitively.
func (c MetadataCarrier) Get(key string) string {
	if v, ok := c[key]; ok {
		return v
	}
	for k, v := range c {
		if strings.EqualFold(k, key) {
			return v
		}
	}
	return ""
}

// Set stores value under key. The underlying map must be non-nil.
func (c MetadataCarrier) Set(key, value string) {
	c[key] = value
}

func (c MetadataCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for k := range c {
		keys = append(keys, k)
	}
	return keys
}

// InjectMetadata writes the trace context and baggage from ctx into md and
// returns it; a nil map is allocated. Use as:
//
//	msg.Metadata = telemetry.InjectMetadata(ctx, msg.Metadata)
func InjectMetadata(ctx context.Context, md message.Metadata) message.Metadata {
	if md == nil {
		md = message.Metadata{}
	}
	otel.GetTextMapPropagator().Inject(ctx, MetadataCarrier(md))
	return md
}

// ExtractMetadata returns ctx enriched with the remote span context and
// baggage found in md (ctx unchanged if there are none). Typically:
//
//	ctx := telemetry.ExtractMetadata(msg.Context(), msg.Metadata)
func ExtractMetadata(ctx context.Context, md message.Metadata) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, MetadataCarrier(md))
}
