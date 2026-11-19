package presentation

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// OwnListUsecase reads one page of an own tab: ListMyLikedUsecase or
// ListMyHistoryUsecase.
type OwnListUsecase interface {
	Execute(ctx context.Context, input usecase.OwnListInput) (usecase.TimedGridPage, error)
}

// OwnListHandler serves an own tab of the caller: GET /api/content/me/liked or
// /api/content/me/history. Both parse and answer the same way and differ in their
// usecase and in the name of the item's time (likedAt, viewedAt). The routes take no
// user id: the list is the token subject's, so no request can read another user's.
type OwnListHandler struct {
	usecase OwnListUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
	// respond maps a page to its API shape, signing the covers.
	respond func(ctx context.Context, page usecase.TimedGridPage, signer usecase.MediaURLSigner) (any, error)
	// failure is the log message of a usecase failure, naming the tab.
	failure string
}

// NewListMyLikedHandler creates the handler of GET /api/content/me/liked. signer is
// handed to the response mapper, which signs the covers.
func NewListMyLikedHandler(uc OwnListUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *OwnListHandler {
	return &OwnListHandler{usecase: uc, signer: signer, logger: logger, failure: "listing the caller's liked posts failed",
		respond: func(ctx context.Context, page usecase.TimedGridPage, signer usecase.MediaURLSigner) (any, error) {
			return usecase.LikedPageToResponse(ctx, page, signer)
		}}
}

// NewListMyHistoryHandler creates the handler of GET /api/content/me/history.
func NewListMyHistoryHandler(uc OwnListUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *OwnListHandler {
	return &OwnListHandler{usecase: uc, signer: signer, logger: logger, failure: "listing the caller's viewing history failed",
		respond: func(ctx context.Context, page usecase.TimedGridPage, signer usecase.MediaURLSigner) (any, error) {
			return usecase.HistoryPageToResponse(ctx, page, signer)
		}}
}

// ServeHTTP answers 200 with { items, nextCursor }, or 400 invalid_cursor or
// invalid_limit.
func (h *OwnListHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
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

	page, err := h.usecase.Execute(r.Context(), usecase.OwnListInput{
		UserID:        userID,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, h.failure, err)
		return
	}

	response, err := h.respond(r.Context(), page, h.signer)
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "signing the tab covers failed", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		h.logger.WarnContext(r.Context(), "writing the tab page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}
