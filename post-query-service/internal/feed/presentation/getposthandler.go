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

// postIDPathValue is the wildcard of GetPostRoute.
const postIDPathValue = "postId"

// GetPostUsecase reads one post as the caller sees it.
type GetPostUsecase interface {
	Execute(ctx context.Context, input usecase.GetPostInput) (usecase.FeedCard, error)
}

// GetPostHandler serves GET /api/content/posts/{postId}.
type GetPostHandler struct {
	usecase GetPostUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
}

// NewGetPostHandler creates a GetPostHandler. signer is handed to the response mapper,
// which signs the post's blob URLs; the handler never builds a SAS itself.
func NewGetPostHandler(uc GetPostUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *GetPostHandler {
	return &GetPostHandler{usecase: uc, signer: signer, logger: logger}
}

// ServeHTTP answers 200 with a PostView, 400 invalid_post_id for an id that is not a
// canonical UUID, and 404 post_not_found when the post is missing, deleted, banned or
// hidden by a block: one answer for all of them, so a block is never revealed.
func (h *GetPostHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}

	postID, ok := h.parsePostID(w, r, userID)
	if !ok {
		return
	}

	card, err := h.usecase.Execute(r.Context(), usecase.GetPostInput{
		UserID:        userID,
		PostID:        postID,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrGetPostNotFound) {
		common.RespondWithError(w, r, http.StatusNotFound, "post_not_found", "Post not found", nil)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "reading a post failed", err)
		return
	}

	view, err := usecase.PostViewToResponse(r.Context(), card, h.signer)
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "signing the post's media urls failed", err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(view); err != nil {
		h.logger.WarnContext(r.Context(), "writing the post view failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}

// parsePostID accepts only the canonical form: uuid.Parse also takes urn:, braced and
// hyphen-less forms. The nil UUID is no post's id.
func (h *GetPostHandler) parsePostID(w http.ResponseWriter, r *http.Request, userID uuid.UUID) (uuid.UUID, bool) {
	raw := r.PathValue(postIDPathValue)
	postID, err := uuid.Parse(raw)
	if err != nil || len(raw) != canonicalUUIDLength || postID == uuid.Nil {
		h.logger.WarnContext(r.Context(), "discarding a post read with a malformed postId", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
		common.RespondWithError(w, r, http.StatusBadRequest, "invalid_post_id", "postId must be a UUID",
			[]common.ApiErrorDetail{{Field: "postId", Issue: "must be a UUID"}})
		return uuid.Nil, false
	}

	return postID, true
}
