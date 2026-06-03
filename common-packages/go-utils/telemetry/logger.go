package telemetry

import (
	"log/slog"
	"os"
)

func initLogger(level slog.Leveler) {
	// Custom handler para interceptar los logs e inyectar el TraceID de OpenTelemetry
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: level,
	})

	otelHandler := &OTelLogHandler{next: handler}

	logger := slog.New(otelHandler)
	slog.SetDefault(logger)
}