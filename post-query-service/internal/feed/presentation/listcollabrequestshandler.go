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

// collabIDPathValue is the wildcard of the collab requests route.
const collabIDPathValue = "collabId"

// statusQueryParam filters the requests list by membership status.
const statusQueryParam = "status"

// ListCollabRequestsUsecase reads one page of a collab's join requests.
type ListCollabRequestsUsecase interface {
	Execute(ctx context.Context, input usecase.ListCollabRequestsInput) (usecase.CollabRequestPage, error)
}

// ListCollabRequestsHandler serves GET /api/content/collabs/{collabId}/requests.
type ListCollabRequestsHandler struct {
	usecase ListCollabRequestsUsecase
	signer  usecase.MediaURLSigner
	logger  *slog.Logger
}

// NewListCollabRequestsHandler creates a ListCollabRequestsHandler. signer is handed to
// the response mapper, which signs the requesters' pictures.
func NewListCollabRequestsHandler(uc ListCollabRequestsUsecase, signer usecase.MediaURLSigner, logger *slog.Logger) *ListCollabRequestsHandler {
	return &ListCollabRequestsHandler{usecase: uc, signer: signer, logger: logger}
}

// ServeHTTP answers 200 with { items, nextCursor }, oldest request first; 400
// invalid_collab_id, invalid_status, invalid_cursor or invalid_limit; 404
// collab_not_found; 403 not_collab_owner for anyone but the collab's owner.
func (h *ListCollabRequestsHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}
	collabID, ok := parsePathUUID(w, r, h.logger, userID, collabIDPathValue, "invalid_collab_id")
	if !ok {
		return
	}
	query := r.URL.Query()
	status := usecase.CollabRequestStatusPending
	if query.Has(statusQueryParam) {
		status = query.Get(statusQueryParam)
		if !usecase.IsCollabMemberStatus(status) {
			h.logger.WarnContext(r.Context(), "discarding a collab requests list with a malformed status", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
			issue := "must be one of PENDING, ACCEPTED, REJECTED, DELETED, LEFT, BANNED"
			common.RespondWithError(w, r, http.StatusBadRequest, "invalid_status", "status "+issue,
				[]common.ApiErrorDetail{{Field: statusQueryParam, Issue: issue}})
			return
		}
	}
	after, ok := parseCursor(w, r, h.logger, userID, query, usecase.DecodeCollabRequestsCursor)
	if !ok {
		return
	}
	limit, ok := parseLimit(w, r, h.logger, userID, query)
	if !ok {
		return
	}

	page, err := h.usecase.Execute(r.Context(), usecase.ListCollabRequestsInput{
		UserID:        userID,
		CollabID:      collabID,
		Status:        status,
		After:         after,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrCollabNotFound) {
		common.RespondWithError(w, r, http.StatusNotFound, "collab_not_found", "Collab not found", nil)
		return
	}
	if errors.Is(err, usecase.ErrNotCollabOwner) {
		common.RespondWithError(w, r, http.StatusForbidden, "not_collab_owner", "Only the collab owner can list its requests", nil)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "listing a collab's requests failed", err)
		return
	}

	response, err := usecase.CollabRequestPageToResponse(r.Context(), page, h.signer)
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "signing the requesters' pictures failed", err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(response); err != nil {
		h.logger.WarnContext(r.Context(), "writing the collab requests page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}
