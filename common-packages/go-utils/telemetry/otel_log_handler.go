package telemetry

import (
	"context"
	"log/slog"

	"go.opentelemetry.io/otel/trace"
)

type OTelLogHandler struct {
	next slog.Handler
}

func (h *OTelLogHandler) Enabled(ctx context.Context, lvl slog.Level) bool { return h.next.Enabled(ctx, lvl) }
func (h *OTelLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler         { return &OTelLogHandler{next: h.next.WithAttrs(attrs)} }
func (h *OTelLogHandler) WithGroup(name string) slog.Handler              { return &OTelLogHandler{next: h.next.WithGroup(name)} }

// This handler injects OpenTelemetry trace_id and span_id into the log record if available in the context
// It must implement the slog.Handler interface to be used as a custom handler in the logger setup
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