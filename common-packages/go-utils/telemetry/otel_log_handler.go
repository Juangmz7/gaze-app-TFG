package telemetry

import (
	"context"
	"log/slog"

	"go.opentelemetry.io/otel/trace"
)

// OTelLogHandler wraps another slog.Handler and adds the trace_id and span_id
// of the span in the record's context (if any) as attributes.
type OTelLogHandler struct {
	next slog.Handler
}

// NewOTelLogHandler returns an OTelLogHandler that forwards to next.
func NewOTelLogHandler(next slog.Handler) *OTelLogHandler {
	return &OTelLogHandler{next: next}
}

func (h *OTelLogHandler) Enabled(ctx context.Context, lvl slog.Level) bool {
	return h.next.Enabled(ctx, lvl)
}

func (h *OTelLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &OTelLogHandler{next: h.next.WithAttrs(attrs)}
}

func (h *OTelLogHandler) WithGroup(name string) slog.Handler {
	return &OTelLogHandler{next: h.next.WithGroup(name)}
}

// Handle injects the OpenTelemetry trace_id and span_id into the record when
// the context carries a valid span context, then forwards it.
func (h *OTelLogHandler) Handle(ctx context.Context, r slog.Record) error {
	if ctx != nil {
		spanContext := trace.SpanFromContext(ctx).SpanContext()
		if spanContext.IsValid() {
			r.AddAttrs(
				slog.String("trace_id", spanContext.TraceID().String()),
				slog.String("span_id", spanContext.SpanID().String()),
			)
		}
	}
	return h.next.Handle(ctx, r)
}
