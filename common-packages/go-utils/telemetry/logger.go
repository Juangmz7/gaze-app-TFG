package telemetry

import (
	"log/slog"
	"os"
)

func initLogger() {
	// Custom handler para interceptar los logs e inyectar el TraceID de OpenTelemetry
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})

	otelHandler := &OTelLogHandler{next: handler}

	logger := slog.New(otelHandler)
	slog.SetDefault(logger)
}