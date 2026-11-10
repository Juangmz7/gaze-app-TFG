package router

import (
	"github.com/ThreeDotsLabs/watermill/message"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
	"go.opentelemetry.io/otel/trace"

	"github.com/Juangmz7/TFG/common-packages/go-utils/telemetry"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/topology"
)

const tracerName = "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/router"

// TracingMiddleware starts one CONSUMER span per delivered message, continuing
// the W3C trace context the publisher put in the AMQP headers (copied into
// the message metadata by the watermill-amqp DefaultMarshaler). The span
// context is stored in the message context, so handlers and their
// *slog.Logger calls (via the *Context variants) are correlated with it.
//
// Register it before the retry middleware so every retry attempt runs inside
// the same span.
func TracingMiddleware(h message.HandlerFunc) message.HandlerFunc {
	tracer := otel.Tracer(tracerName)

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

		produced, err := h(msg)
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, err.Error())
		}
		return produced, err
	}
}
