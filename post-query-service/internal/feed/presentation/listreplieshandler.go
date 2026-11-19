package presentation

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// ListRepliesUsecase reads one page of the replies of a comment.
type ListRepliesUsecase interface {
	Execute(ctx context.Context, input usecase.ListRepliesInput) (usecase.CommentPage, error)
}

// ListRepliesHandler serves GET /api/content/posts/{postId}/comments/{commentId}/replies.
// It shares its parameter parsing and response writing with ListCommentsHandler
// (listcommentshandler.go).
type ListRepliesHandler struct {
	usecase ListRepliesUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
}

// NewListRepliesHandler creates a ListRepliesHandler. signer is handed to the response
// mapper, which signs the authors' pictures.
func NewListRepliesHandler(uc ListRepliesUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *ListRepliesHandler {
	return &ListRepliesHandler{usecase: uc, signer: signer, logger: logger}
}

// ServeHTTP answers 200 with { items, nextCursor }; 400 invalid_post_id,
// invalid_comment_id, invalid_cursor or invalid_limit; 404 post_not_found or
// comment_not_found.
func (h *ListRepliesHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}
	postID, ok := parsePathUUID(w, r, h.logger, userID, postIDPathValue, "invalid_post_id")
	if !ok {
		return
	}
	commentID, ok := parsePathUUID(w, r, h.logger, userID, commentIDPathValue, "invalid_comment_id")
	if !ok {
		return
	}
	query := r.URL.Query()
	after, ok := parseCursor(w, r, h.logger, userID, query, usecase.DecodeRepliesCursor)
	if !ok {
		return
	}
	limit, ok := parseCommentLimit(w, r, h.logger, userID, query)
	if !ok {
		return
	}

	page, err := h.usecase.Execute(r.Context(), usecase.ListRepliesInput{
		UserID:        userID,
		PostID:        postID,
		CommentID:     commentID,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrCommentsPostNotFound) {
		respondPostNotFound(w, r)
		return
	}
	if errors.Is(err, usecase.ErrCommentNotFound) {
		common.RespondWithError(w, r, http.StatusNotFound, "comment_not_found", "Comment not found", nil)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "listing a comment's replies failed", err)
		return
	}

	respondWithCommentPage(w, r, h.logger, userID, page, h.signer)
}
