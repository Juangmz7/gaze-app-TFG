package presentation

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// commentIDPathValue is the wildcard of ListRepliesRoute.
const commentIDPathValue = "commentId"

// Query parameters of the comment lists.
const (
	cursorQueryParam = "cursor"
	limitQueryParam  = "limit"
)

// maxLimitDigits bounds the limit parameter before it is parsed: CommentsMaxLimit has
// two digits.
const maxLimitDigits = 2

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
	limit, ok := parseCommentLimit(w, r, h.logger, userID, query)
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

// parsePathUUID reads a path wildcard that must be a canonical, non-nil UUID, and
// answers 400 with code otherwise. uuid.Parse alone also takes urn:, braced and
// hyphen-less forms.
func parsePathUUID(w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, name, code string) (uuid.UUID, bool) {
	raw := r.PathValue(name)
	id, err := uuid.Parse(raw)
	if err != nil || len(raw) != canonicalUUIDLength || id == uuid.Nil {
		logger.WarnContext(r.Context(), "discarding a content read with a malformed "+name, "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
		common.RespondWithError(w, r, http.StatusBadRequest, code, name+" must be a UUID",
			[]common.ApiErrorDetail{{Field: name, Issue: "must be a UUID"}})
		return uuid.Nil, false
	}

	return id, true
}

// parseCursor decodes the optional cursor with decode, answering 400 invalid_cursor
// for a cursor this list did not produce, including an empty one.
func parseCursor[C any](w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, query url.Values, decode func(string) (C, error)) (*C, bool) {
	if !query.Has(cursorQueryParam) {
		return nil, true
	}

	cursor, err := decode(query.Get(cursorQueryParam))
	if err != nil {
		logger.WarnContext(r.Context(), "discarding a comment list with a malformed cursor", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
		common.RespondWithError(w, r, http.StatusBadRequest, "invalid_cursor", "cursor is not valid for this list",
			[]common.ApiErrorDetail{{Field: cursorQueryParam, Issue: "must be a nextCursor returned by this list"}})
		return nil, false
	}

	return &cursor, true
}

// parseCommentLimit reads the optional limit: decimal digits only, 1..CommentsMaxLimit,
// CommentsDefaultLimit when absent. Anything else is 400 invalid_limit.
func parseCommentLimit(w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, query url.Values) (int, bool) {
	if !query.Has(limitQueryParam) {
		return usecase.CommentsDefaultLimit, true
	}

	raw := query.Get(limitQueryParam)
	limit, err := strconv.Atoi(raw)
	if err != nil || !isDecimalDigits(raw) || len(raw) > maxLimitDigits || limit < 1 || limit > usecase.CommentsMaxLimit {
		logger.WarnContext(r.Context(), "discarding a comment list with a malformed limit", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
		issue := "must be an integer between 1 and " + strconv.Itoa(usecase.CommentsMaxLimit)
		common.RespondWithError(w, r, http.StatusBadRequest, "invalid_limit", "limit "+issue,
			[]common.ApiErrorDetail{{Field: limitQueryParam, Issue: issue}})
		return 0, false
	}

	return limit, true
}

// isDecimalDigits rejects the signs and spaces strconv.Atoi would accept.
func isDecimalDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, character := range value {
		if character < '0' || character > '9' {
			return false
		}
	}

	return true
}
