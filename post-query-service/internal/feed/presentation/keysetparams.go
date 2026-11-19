package presentation

import (
	"log/slog"
	"net/http"
	"net/url"
	"strconv"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// Request parsing every keyset-paged list of /api/content shares: the comment lists
// (task 48) and the profile grids (task 49).

// Query parameters of the keyset lists.
const (
	cursorQueryParam = "cursor"
	limitQueryParam  = "limit"
)

// maxLimitDigits bounds the limit parameter before it is parsed: KeysetMaxLimit has
// two digits.
const maxLimitDigits = 2

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
		logger.WarnContext(r.Context(), "discarding a list request with a malformed cursor", "correlation_id", correlationIDFrom(r.Context()), "userId", userID, "error", err)
		common.RespondWithError(w, r, http.StatusBadRequest, "invalid_cursor", "cursor is not valid for this list",
			[]common.ApiErrorDetail{{Field: cursorQueryParam, Issue: "must be a nextCursor returned by this list"}})
		return nil, false
	}

	return &cursor, true
}

// parseLimit reads the optional limit: decimal digits only, 1..KeysetMaxLimit,
// KeysetDefaultLimit when absent. Anything else is 400 invalid_limit.
func parseLimit(w http.ResponseWriter, r *http.Request, logger *slog.Logger, userID uuid.UUID, query url.Values) (int, bool) {
	if !query.Has(limitQueryParam) {
		return usecase.KeysetDefaultLimit, true
	}

	raw := query.Get(limitQueryParam)
	limit, err := strconv.Atoi(raw)
	if err != nil || !isDecimalDigits(raw) || len(raw) > maxLimitDigits || limit < 1 || limit > usecase.KeysetMaxLimit {
		logger.WarnContext(r.Context(), "discarding a list request with a malformed limit", "correlation_id", correlationIDFrom(r.Context()), "userId", userID)
		issue := "must be an integer between 1 and " + strconv.Itoa(usecase.KeysetMaxLimit)
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
