// Package logging shapes the service's log records.
package logging

import (
	"context"
	"log/slog"
	"strconv"
	"strings"
)

// InlineAttributesHandler appends a record's attributes to its message as key=value and
// keeps them as attributes too, so they can still be filtered on. Grafana lists an OTLP
// log by its body alone and shows the attributes only when a line is expanded: without
// this a line reads "feed disconnect recorded", with no user or correlation id.
type InlineAttributesHandler struct {
	next slog.Handler
	// prefix qualifies the keys inside a WithGroup, as slog's JSON handler nests them.
	prefix string
	// inline holds the attributes of With, already rendered.
	inline string
}

// NewInlineAttributesHandler wraps next.
func NewInlineAttributesHandler(next slog.Handler) *InlineAttributesHandler {
	return &InlineAttributesHandler{next: next}
}

// Enabled implements slog.Handler.
func (h *InlineAttributesHandler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.next.Enabled(ctx, level)
}

// Handle implements slog.Handler.
func (h *InlineAttributesHandler) Handle(ctx context.Context, record slog.Record) error {
	var message strings.Builder
	message.WriteString(record.Message)
	message.WriteString(h.inline)
	record.Attrs(func(attr slog.Attr) bool {
		appendAttr(&message, h.prefix, attr)
		return true
	})

	inlined := slog.NewRecord(record.Time, record.Level, message.String(), record.PC)
	record.Attrs(func(attr slog.Attr) bool {
		inlined.AddAttrs(attr)
		return true
	})

	return h.next.Handle(ctx, inlined)
}

// WithAttrs implements slog.Handler.
func (h *InlineAttributesHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	var inline strings.Builder
	inline.WriteString(h.inline)
	for _, attr := range attrs {
		appendAttr(&inline, h.prefix, attr)
	}

	return &InlineAttributesHandler{next: h.next.WithAttrs(attrs), prefix: h.prefix, inline: inline.String()}
}

// WithGroup implements slog.Handler.
func (h *InlineAttributesHandler) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}

	return &InlineAttributesHandler{next: h.next.WithGroup(name), prefix: h.prefix + name + ".", inline: h.inline}
}

func appendAttr(message *strings.Builder, prefix string, attr slog.Attr) {
	attr.Value = attr.Value.Resolve()
	if attr.Equal(slog.Attr{}) {
		return
	}
	if attr.Value.Kind() == slog.KindGroup {
		groupPrefix := prefix
		if attr.Key != "" {
			groupPrefix += attr.Key + "."
		}
		for _, member := range attr.Value.Group() {
			appendAttr(message, groupPrefix, member)
		}
		return
	}

	message.WriteByte(' ')
	message.WriteString(prefix)
	message.WriteString(attr.Key)
	message.WriteByte('=')
	message.WriteString(formatValue(attr.Value))
}

// formatValue quotes a value that would not read as one token: empty, or with spaces,
// quotes or an equals sign, like an error message.
func formatValue(value slog.Value) string {
	text := value.String()
	if text == "" || strings.ContainsAny(text, " \t\r\n\"=") {
		return strconv.Quote(text)
	}

	return text
}
