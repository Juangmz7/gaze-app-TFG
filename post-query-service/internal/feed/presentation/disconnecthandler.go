package presentation

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// DisconnectUsecase records a disconnect.
type DisconnectUsecase interface {
	Execute(ctx context.Context, input usecase.RegisterUserDisconnectedInput) error
}

// DisconnectHandler serves POST /api/feed/disconnect. The request has no body to
// read: the user comes from the token.
type DisconnectHandler struct {
	usecase DisconnectUsecase
	logger  *slog.Logger
}

// NewDisconnectHandler creates a DisconnectHandler.
func NewDisconnectHandler(uc DisconnectUsecase, logger *slog.Logger) *DisconnectHandler {
	return &DisconnectHandler{usecase: uc, logger: logger}
}

// ServeHTTP answers 202 once the disconnect is recorded. The usecase protects the
// write from the client closing the connection.
func (h *DisconnectHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}

	input := usecase.RegisterUserDisconnectedInput{UserID: userID, CorrelationID: correlationIDFrom(r.Context())}
	if err := h.usecase.Execute(r.Context(), input); err != nil {
		respondWithFailure(w, r, h.logger, userID, "feed disconnect failed", err)
		return
	}

	w.WriteHeader(http.StatusAccepted)
}
