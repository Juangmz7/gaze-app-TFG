package telemetry

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	sdklog "go.opentelemetry.io/otel/sdk/log"
)

// InitLogger sets the default slog logger to JSON on stdout with trace_id /
// span_id correlation, without OTLP export. Init replaces it with a logger
// that also exports over OTLP; call InitLogger only for processes that do
// not call Init, or to get JSON logs before Init runs.
func InitLogger(level slog.Leveler) {
	slog.SetDefault(slog.New(newStdoutHandler(os.Stdout, level)))
}

func newStdoutHandler(w io.Writer, level slog.Leveler) slog.Handler {
	return NewOTelLogHandler(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: level}))
}

// newLogHandler builds the handler installed by Init: JSON stdout (with
// trace correlation attributes) plus, when lp is non-nil, the OTLP bridge.
// The OTLP bridge attaches the span context natively, so it does not need
// the trace_id/span_id attributes.
func newLogHandler(cfg Config, lp *sdklog.LoggerProvider) slog.Handler {
	level := cfg.LogLevel
	if level == nil {
		level = slog.LevelInfo
	}
	out := cfg.LogOutput
	if out == nil {
		out = os.Stdout
	}

	stdout := newStdoutHandler(out, level)
	if lp == nil {
		return stdout
	}
	otlp := &levelHandler{
		level: level,
		next:  otelslog.NewHandler(instrumentationName, otelslog.WithLoggerProvider(lp)),
	}
	return NewFanoutHandler(stdout, otlp)
}

// FanoutHandler forwards every record to all handlers that are enabled for
// its level. Each handler receives its own clone of the record, so handlers
// that add attributes (like OTelLogHandler) do not affect the others.
type FanoutHandler struct {
	handlers []slog.Handler
}

// NewFanoutHandler returns a handler that dispatches to all handlers.
func NewFanoutHandler(handlers ...slog.Handler) *FanoutHandler {
	return &FanoutHandler{handlers: handlers}
}

func (h *FanoutHandler) Enabled(ctx context.Context, lvl slog.Level) bool {
	ctx = nonNilContext(ctx)
	for _, handler := range h.handlers {
		if handler.Enabled(ctx, lvl) {
			return true
		}
	}
	return false
}

// Handle calls every enabled handler, even if an earlier one fails, and
// returns the joined errors.
func (h *FanoutHandler) Handle(ctx context.Context, r slog.Record) error {
	ctx = nonNilContext(ctx)
	var errs []error
	for _, handler := range h.handlers {
		if !handler.Enabled(ctx, r.Level) {
			continue
		}
		if err := handler.Handle(ctx, r.Clone()); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

func (h *FanoutHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	next := make([]slog.Handler, len(h.handlers))
	for i, handler := range h.handlers {
		next[i] = handler.WithAttrs(attrs)
	}
	return &FanoutHandler{handlers: next}
}

func (h *FanoutHandler) WithGroup(name string) slog.Handler {
	next := make([]slog.Handler, len(h.handlers))
	for i, handler := range h.handlers {
		next[i] = handler.WithGroup(name)
	}
	return &FanoutHandler{handlers: next}
}

// levelHandler applies a minimum level to a handler that has none of its own
// (the otelslog bridge only filters through the LoggerProvider).
type levelHandler struct {
	level slog.Leveler
	next  slog.Handler
}

func (h *levelHandler) Enabled(ctx context.Context, lvl slog.Level) bool {
	return lvl >= h.level.Level() && h.next.Enabled(ctx, lvl)
}

func (h *levelHandler) Handle(ctx context.Context, r slog.Record) error {
	return h.next.Handle(ctx, r)
}

func (h *levelHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &levelHandler{level: h.level, next: h.next.WithAttrs(attrs)}
}

func (h *levelHandler) WithGroup(name string) slog.Handler {
	return &levelHandler{level: h.level, next: h.next.WithGroup(name)}
}

func nonNilContext(ctx context.Context) context.Context {
	if ctx == nil {
		return context.Background()
	}
	return ctx
}
