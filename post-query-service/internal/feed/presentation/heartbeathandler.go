package presentation

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// HeartbeatUsecase records a heartbeat.
type HeartbeatUsecase interface {
	Execute(ctx context.Context, input usecase.RegisterHeartbeatInput) error
}

// HeartbeatHandler serves POST /api/feed/heartbeat. The request has no body to read:
// the user comes from the token.
type HeartbeatHandler struct {
	usecase HeartbeatUsecase
	logger  *slog.Logger
}

// NewHeartbeatHandler creates a HeartbeatHandler.
func NewHeartbeatHandler(uc HeartbeatUsecase, logger *slog.Logger) *HeartbeatHandler {
	return &HeartbeatHandler{usecase: uc, logger: logger}
}

// ServeHTTP answers 204 once the heartbeat is recorded.
func (h *HeartbeatHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}

	input := usecase.RegisterHeartbeatInput{UserID: userID, CorrelationID: correlationIDFrom(r.Context())}
	if err := h.usecase.Execute(r.Context(), input); err != nil {
		respondWithFailure(w, r, h.logger, userID, "feed heartbeat failed", err)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
