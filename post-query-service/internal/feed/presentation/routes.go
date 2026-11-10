// Package presentation exposes the feed over HTTP. Handlers parse the request,
// delegate to one usecase and encode the answer; they hold no business logic.
package presentation

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
)

// Feed routes.
const (
	HeartbeatRoute  = "POST /api/feed/heartbeat"
	DisconnectRoute = "POST /api/feed/disconnect"
	ClaimFeedRoute  = "GET /api/feed"
)

// CorrelationIDHeader carries the correlation id of a request.
const CorrelationIDHeader = "X-Correlation-Id"

// maxCorrelationIDLength bounds what a client can make the service log per request.
const maxCorrelationIDLength = 128

// statusClientClosedRequest answers a request whose client went away: not a server
// failure, so it should not read as one. The client is not there to see it, but
// logs and traces are.
const statusClientClosedRequest = 499

// Routes holds the feed's HTTP handlers.
type Routes struct {
	Heartbeat  *HeartbeatHandler
	Disconnect *DisconnectHandler
	ClaimFeed  *ClaimFeedHandler
}

// RegisterRoutes adds the three feed endpoints to mux behind authenticate
// (auth.Authenticate in production). Only these routes are wrapped, so the health
// endpoints stay unauthenticated.
func RegisterRoutes(mux *http.ServeMux, authenticate func(http.Handler) http.Handler, routes Routes) {
	register := func(pattern string, handler http.Handler) {
		mux.Handle(pattern, withCorrelationID(authenticate(handler)))
	}

	register(HeartbeatRoute, routes.Heartbeat)
	register(DisconnectRoute, routes.Disconnect)
	register(ClaimFeedRoute, routes.ClaimFeed)
}

type correlationIDKey struct{}

// withCorrelationID puts the request's correlation id in its context and echoes it
// in the response: the header when it is usable, otherwise a new one.
func withCorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get(CorrelationIDHeader)
		if !isUsableCorrelationID(correlationID) {
			correlationID = uuid.NewString()
		}

		w.Header().Set(CorrelationIDHeader, correlationID)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), correlationIDKey{}, correlationID)))
	})
}

// isUsableCorrelationID rejects values that are empty, oversized or not printable
// ASCII, because the value ends up in logs and in a response header.
func isUsableCorrelationID(value string) bool {
	if value == "" || len(value) > maxCorrelationIDLength {
		return false
	}

	return !strings.ContainsFunc(value, func(r rune) bool { return r <= ' ' || r >= 0x7f })
}

func correlationIDFrom(ctx context.Context) string {
	correlationID, _ := ctx.Value(correlationIDKey{}).(string)
	return correlationID
}

// requireUserID resolves the caller from the authenticated context, the only source
// of a userId on these endpoints. It answers 401 and reports false when the token has
// no subject, or one that is not a UUID.
func requireUserID(w http.ResponseWriter, r *http.Request, logger *slog.Logger) (uuid.UUID, bool) {
	subject, found := auth.SubjectFromContext(r.Context())
	if !found {
		logger.WarnContext(r.Context(), "discarding a feed request without a token subject", "correlation_id", correlationIDFrom(r.Context()))
		common.RespondWithError(w, r, http.StatusUnauthorized, "invalid_token", "Invalid token", nil)
		return uuid.Nil, false
	}

	userID, err := uuid.Parse(subject)
	if err != nil || userID == uuid.Nil || len(subject) != len(userID.String()) {
		logger.WarnContext(r.Context(), "discarding a feed request whose token subject is not a UUID", "correlation_id", correlationIDFrom(r.Context()))
		common.RespondWithError(w, r, http.StatusUnauthorized, "invalid_token", "Invalid token", nil)
		return uuid.Nil, false
	}

	return userID, true
}

// respondWithFailure logs a usecase failure and answers it.
func respondWithFailure(w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, message string, err error) {
	attributes := []any{"correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err}

	if errors.Is(err, context.Canceled) {
		logger.WarnContext(r.Context(), message+": the client went away", attributes...)
		common.RespondWithError(w, r, statusClientClosedRequest, "client_closed_request", "Client closed the request", nil)
		return
	}

	logger.ErrorContext(r.Context(), message, attributes...)
	common.RespondWithError(w, r, http.StatusInternalServerError, "internal_server_error", "Internal server error", nil)
}
