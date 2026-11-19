package presentation

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// FollowingFeedUsecase reads one page of the caller's following feed.
type FollowingFeedUsecase interface {
	Execute(ctx context.Context, input usecase.FollowingFeedInput) (usecase.FollowingFeedPage, error)
}

// FollowingFeedHandler serves GET /api/feed/following. It shares the /api/feed route
// and its authentication with the recommended feed but none of its state: nothing is
// marked served and no prefetch is triggered.
type FollowingFeedHandler struct {
	usecase FollowingFeedUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
}

// NewFollowingFeedHandler creates a FollowingFeedHandler. signer is handed to the
// response mapper, which signs the posts' blob URLs.
func NewFollowingFeedHandler(uc FollowingFeedUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *FollowingFeedHandler {
	return &FollowingFeedHandler{usecase: uc, signer: signer, logger: logger}
}

// ServeHTTP answers 200 with { items: PostView[], nextCursor }, and 400 invalid_cursor
// or invalid_limit. The caller is the token subject only.
func (h *FollowingFeedHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
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

	page, err := h.usecase.Execute(r.Context(), usecase.FollowingFeedInput{
		UserID:        userID,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "reading the following feed failed", err)
		return
	}

	response, err := usecase.FollowingFeedPageToResponse(r.Context(), page, h.signer)
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "signing the following feed's media urls failed", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		h.logger.WarnContext(r.Context(), "writing the following feed page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}
