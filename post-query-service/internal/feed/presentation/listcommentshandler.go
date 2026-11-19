package presentation

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// commentIDPathValue is the wildcard of ListRepliesRoute.
const commentIDPathValue = "commentId"

// ListCommentsUsecase reads one page of a post's top-level comments.
type ListCommentsUsecase interface {
	Execute(ctx context.Context, input usecase.ListCommentsInput) (usecase.CommentPage, error)
}

// ListCommentsHandler serves GET /api/content/posts/{postId}/comments.
type ListCommentsHandler struct {
	usecase ListCommentsUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
}

// NewListCommentsHandler creates a ListCommentsHandler. signer is handed to the
// response mapper, which signs the authors' pictures.
func NewListCommentsHandler(uc ListCommentsUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *ListCommentsHandler {
	return &ListCommentsHandler{usecase: uc, signer: signer, logger: logger}
}

// ServeHTTP answers 200 with { items, nextCursor }; 400 invalid_post_id, invalid_cursor
// or invalid_limit; 404 post_not_found when the post is missing, deleted, banned or
// hidden by a block.
func (h *ListCommentsHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}
	postID, ok := parsePathUUID(w, r, h.logger, userID, postIDPathValue, "invalid_post_id")
	if !ok {
		return
	}
	query := r.URL.Query()
	after, ok := parseCursor(w, r, h.logger, userID, query, usecase.DecodeCommentsCursor)
	if !ok {
		return
	}
	limit, ok := parseLimit(w, r, h.logger, userID, query)
	if !ok {
		return
	}

	page, err := h.usecase.Execute(r.Context(), usecase.ListCommentsInput{
		UserID:        userID,
		PostID:        postID,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrCommentsPostNotFound) {
		respondPostNotFound(w, r)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "listing a post's comments failed", err)
		return
	}

	respondWithCommentPage(w, r, h.logger, userID, page, h.signer)
}

// respondPostNotFound is the one 404 of a post the caller may not see.
func respondPostNotFound(w http.ResponseWriter, r *http.Request) {
	common.RespondWithError(w, r, http.StatusNotFound, "post_not_found", "Post not found", nil)
}

// respondWithCommentPage maps a page, signing the authors' pictures, and writes it.
func respondWithCommentPage(w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, page usecase.CommentPage, signer usecase.MediaURLSigner) {
	response, err := usecase.CommentPageToResponse(r.Context(), page, signer)
	if err != nil {
		respondWithFailure(w, r, logger, userID, "signing the comment authors' pictures failed", err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		logger.WarnContext(r.Context(), "writing the comment page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}
