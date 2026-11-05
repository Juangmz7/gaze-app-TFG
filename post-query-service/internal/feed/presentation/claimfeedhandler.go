package presentation

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"strconv"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// canonicalUUIDLength is the length of the only form lastSeenId is accepted in:
// uuid.Parse also takes urn:, braced and hyphen-less forms.
const canonicalUUIDLength = 36

// ClaimFeedUsecase serves a page of the feed.
type ClaimFeedUsecase interface {
	Execute(ctx context.Context, input usecase.ClaimFeedInput) (usecase.ClaimFeedOutput, error)
}

// ClaimFeedHandler serves GET /api/feed?lastSeenId=<postId|omitted>&limit=<n>.
type ClaimFeedHandler struct {
	usecase ClaimFeedUsecase
	logger  *slog.Logger
}

// NewClaimFeedHandler creates a ClaimFeedHandler.
func NewClaimFeedHandler(uc ClaimFeedUsecase, logger *slog.Logger) *ClaimFeedHandler {
	return &ClaimFeedHandler{usecase: uc, logger: logger}
}

// ServeHTTP answers 200 with a page, which can be short or empty, 400 for a malformed
// cursor or a limit that is not a positive integer up to the maximum, and 503 with
// Retry-After when no page can be built right now.
func (h *ClaimFeedHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	userID, ok := requireUserID(w, r, h.logger)
	if !ok {
		return
	}

	query := r.URL.Query()
	lastSeenID, ok := h.parseLastSeenID(w, r, userID, query)
	if !ok {
		return
	}
	limit, ok := h.parseLimit(w, r, userID, query)
	if !ok {
		return
	}

	output, err := h.usecase.Execute(r.Context(), usecase.ClaimFeedInput{
		UserID:        userID,
		LastSeenID:    lastSeenID,
		Limit:         limit,
		CorrelationID: correlationIDFrom(r.Context()),
	})
	if errors.Is(err, usecase.ErrClaimFeedInvalidLimit) {
		h.rejectLimit(w, r, userID)
		return
	}
	var unavailable *usecase.FeedTemporarilyUnavailableError
	if errors.As(err, &unavailable) {
		respondFeedTemporarilyUnavailable(w, r, unavailable)
		return
	}
	if err != nil {
		respondWithFailure(w, r, h.logger, userID, "feed claim failed", err)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(usecase.FeedPageToResponse(output)); err != nil {
		h.logger.WarnContext(r.Context(), "writing the feed page failed", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
	}
}

// respondFeedTemporarilyUnavailable answers the degraded response. The usecase logged
// it, with its cause. The body says nothing of the cause, which is internal.
func respondFeedTemporarilyUnavailable(w http.ResponseWriter, r *http.Request, unavailable *usecase.FeedTemporarilyUnavailableError) {
	w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(unavailable.RetryAfter.Seconds()))))
	common.RespondWithError(w, r, http.StatusServiceUnavailable, "feed_temporarily_unavailable", "The feed is temporarily unavailable, try again shortly", nil)
}

func (h *ClaimFeedHandler) parseLastSeenID(w http.ResponseWriter, r *http.Request, userID uuid.UUID, query url.Values) (*uuid.UUID, bool) {
	if !query.Has("lastSeenId") {
		return nil, true
	}

	raw := query.Get("lastSeenId")
	lastSeenID, err := uuid.Parse(raw)
	if err != nil || len(raw) != canonicalUUIDLength {
		h.logger.WarnContext(r.Context(), "discarding a feed claim with a malformed lastSeenId", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
		common.RespondWithError(w, r, http.StatusBadRequest, "invalid_last_seen_id", "lastSeenId must be a UUID",
			[]common.ApiErrorDetail{{Field: "lastSeenId", Issue: "must be a UUID"}})
		return nil, false
	}

	return &lastSeenID, true
}

func (h *ClaimFeedHandler) parseLimit(w http.ResponseWriter, r *http.Request, userID uuid.UUID, query url.Values) (*int, bool) {
	if !query.Has("limit") {
		return nil, true
	}

	limit, err := strconv.Atoi(query.Get("limit"))
	if err != nil {
		h.rejectLimit(w, r, userID)
		return nil, false
	}

	return &limit, true
}

func (h *ClaimFeedHandler) rejectLimit(w http.ResponseWriter, r *http.Request, userID uuid.UUID) {
	h.logger.WarnContext(r.Context(), "discarding a feed claim with an invalid limit", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
	common.RespondWithError(w, r, http.StatusBadRequest, "invalid_limit", "limit must be a positive integer not above the maximum page size",
		[]common.ApiErrorDetail{{Field: "limit", Issue: "must be a positive integer not above the maximum page size"}})
}
