// Package health exposes the Kubernetes liveness and readiness probes of
// post-query-service. Liveness only reports that the process is serving
// HTTP; readiness also pings MongoDB so the pod is taken out of rotation
// while its read-model store is unreachable.
package health

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"
)

// LivePath is the liveness probe path.
const LivePath = "/healthz/live"

// ReadyPath is the readiness probe path.
const ReadyPath = "/healthz/ready"

// readyTimeout bounds the MongoDB ping of a readiness check. It must stay
// below the probe timeoutSeconds configured in the Helm chart.
const readyTimeout = 2 * time.Second

// Register adds the liveness and readiness handlers to mux.
func Register(mux *http.ServeMux, client *mongo.Client) {
	mux.HandleFunc("GET "+LivePath, func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	mux.HandleFunc("GET "+ReadyPath, func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), readyTimeout)
		defer cancel()

		if err := client.Ping(ctx, nil); err != nil {
			slog.Warn("readiness check failed", "error", err)
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
}
