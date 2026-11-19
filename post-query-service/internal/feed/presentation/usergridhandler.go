package presentation

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// userIDPathValue is the wildcard of the profile grid routes.
const userIDPathValue = "userId"

// UserGridUsecase reads one page of a profile grid: ListUserPostsUsecase or
// ListUserRepostsUsecase.
type UserGridUsecase interface {
	Execute(ctx context.Context, input usecase.UserGridInput) (usecase.GridPage, error)
}

// UserGridHandler serves a profile grid: GET /api/content/users/{userId}/posts or
// /reposts. Both parse and answer the same way and differ only in their usecase.
type UserGridHandler struct {
	usecase UserGridUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
	// failure is the log message of a usecase failure, naming the grid.
	failure string
}

// NewListUserPostsHandler creates the handler of GET /api/content/users/{userId}/posts.
// signer is handed to the response mapper, which signs the covers.
func NewListUserPostsHandler(uc UserGridUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *UserGridHandler {
	return &UserGridHandler{usecase: uc, signer: signer, logger: logger, failure: "listing a user's posts failed"}
}

// NewListUserRepostsHandler creates the handler of GET
// /api/content/users/{userId}/reposts.
func NewListUserRepostsHandler(uc UserGridUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *UserGridHandler {
	return &UserGridHandler{usecase: uc, signer: signer, logger: logger, failure: "listing a user's reposts failed"}
}

// ServeHTTP answers 200 with { items, nextCursor }; 400 invalid_user_id, invalid_cursor
// or invalid_limit; 404 user_not_found when the user is unknown, deleted or in a block
// relation with the caller.
func (h *UserGridHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}
	targetUserID, ok := parsePathUUID(w, r, h.logger, userID, userIDPathValue, "invalid_user_id")
	if !ok {
		return
	}
	query := r.URL.Query()
	after, ok := parseCursor(w, r, h.logger, userID, query, usecase.DecodeGridCursor)
	if !ok {
		return
	}
	limit, ok := parseLimit(w, r, h.logger, userID, query)
	if !ok {
		return
	}

	page, err := h.usecase.Execute(r.Context(), usecase.UserGridInput{
		UserID:        userID,
		TargetUserID:  targetUserID,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrGridUserNotFound) {
		common.RespondWithError(w, r, http.StatusNotFound, "user_not_found", "User not found", nil)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, h.failure, err)
		return
	}

	response, err := usecase.GridPageToResponse(r.Context(), page, h.signer)
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "signing the grid covers failed", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		h.logger.WarnContext(r.Context(), "writing the grid page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}
