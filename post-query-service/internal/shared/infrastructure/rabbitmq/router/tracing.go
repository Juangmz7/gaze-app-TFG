package router

import (
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
	"go.opentelemetry.io/otel/trace"

	"github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

const tracerName = "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"

// errorTypeHandler is the error.type of a message whose handler returned an error.
const errorTypeHandler = "handler_error"

// TracingMiddleware starts one CONSUMER span per delivered message, continuing
// the W3C trace context the publisher put in the AMQP headers (copied into
// the message metadata by the watermill-amqp DefaultMarshaler). The span
// context is stored in the message context, so handlers and their
// *slog.Logger calls (via the *Context variants) are correlated with it.
//
// It also records messaging.process.duration per queue, with error.type set when
// the handler failed, so the consume rate, error rate and latency of each queue
// are available as metrics.
//
// Register it before the retry middleware so every retry attempt runs inside
// the same span: the duration then covers the retries too.
func TracingMiddleware(h message.HandlerFunc) message.HandlerFunc {
	tracer := otel.Tracer(tracerName)
	// A failed instrument is replaced by a no-op one by the SDK: err is only logged there
	processDuration, _ := otel.Meter(tracerName).Float64Histogram("messaging.process.duration",
		metric.WithUnit("s"),
		metric.WithDescription("Time to process a consumed message, retries included."),
		metric.WithExplicitBucketBoundaries(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120))

	return func(msg *message.Message) ([]*message.Message, error) {
		queue := message.SubscribeTopicFromCtx(msg.Context())
		routingKey := msg.Metadata.Get(topology.RoutingKeyHeader)

		ctx := telemetry.ExtractMetadata(msg.Context(), msg.Metadata)
		ctx, span := tracer.Start(ctx, "process "+queue,
			trace.WithSpanKind(trace.SpanKindConsumer),
			trace.WithAttributes(
				semconv.MessagingSystemRabbitMQ,
				semconv.MessagingOperationTypeProcess,
				semconv.MessagingDestinationName(queue),
				semconv.MessagingRabbitMQDestinationRoutingKey(routingKey),
				semconv.MessagingMessageID(msg.UUID),
				semconv.MessagingConsumerGroupName(message.HandlerNameFromCtx(msg.Context())),
			),
		)
		defer span.End()

		msg.SetContext(ctx)

		started := time.Now()
		produced, err := h(msg)
		attributes := []attribute.KeyValue{
			semconv.MessagingSystemRabbitMQ,
			semconv.MessagingDestinationName(queue),
		}
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, err.Error())
			attributes = append(attributes, semconv.ErrorTypeKey.String(errorTypeHandler))
		}
		processDuration.Record(ctx, time.Since(started).Seconds(), metric.WithAttributes(attributes...))
		return produced, err
	}
}
