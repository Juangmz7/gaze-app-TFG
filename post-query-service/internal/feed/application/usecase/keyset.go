package usecase

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// What every keyset-paged list of /api/content shares (the comment lists of task 48,
// the profile grids of task 49): the page size contract and the opaque cursor codec.
// Each list keeps its own cursor type, whose JSON fields differ, so a cursor of one
// list shape is rejected by another.

// Page sizes of the keyset lists: part of the API contract, not a setting.
const (
	// KeysetDefaultLimit is the page size when the client sends none.
	KeysetDefaultLimit = 20
	// KeysetMaxLimit is the largest page a client may ask for.
	KeysetMaxLimit = 50
)

// ErrInvalidCursor is returned for a cursor that was not produced by this service for
// that list: not base64url, not the expected JSON, a missing or unknown field, or a
// value out of range.
var ErrInvalidCursor = errors.New("invalid cursor")

// maxCursorLength bounds what a client can make the service decode. A real cursor is
// about 120 characters.
const maxCursorLength = 256

// canonicalIDLength is the length of the only form a cursor id is accepted in.
const canonicalIDLength = 36

// validLimit checks the page size every keyset list input must meet.
func validLimit(limit int) error {
	if limit < 1 || limit > KeysetMaxLimit {
		return fmt.Errorf("limit %d is outside 1..%d", limit, KeysetMaxLimit)
	}

	return nil
}

func encodeCursor(wire any) string {
	// Marshalling a struct of pointers to numbers and strings cannot fail.
	payload, _ := json.Marshal(wire)
	return base64.RawURLEncoding.EncodeToString(payload)
}

// decodeCursor decodes base64url without padding into exactly one JSON object with
// no unknown field.
func decodeCursor(raw string, wire any) error {
	if raw == "" || len(raw) > maxCursorLength {
		return fmt.Errorf("%w: empty or longer than %d characters", ErrInvalidCursor, maxCursorLength)
	}
	payload, err := base64.RawURLEncoding.Strict().DecodeString(raw)
	if err != nil {
		return fmt.Errorf("%w: not base64url: %v", ErrInvalidCursor, err)
	}

	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(wire); err != nil {
		return fmt.Errorf("%w: not the expected JSON: %v", ErrInvalidCursor, err)
	}
	if decoder.More() {
		return fmt.Errorf("%w: trailing data", ErrInvalidCursor)
	}

	return nil
}

// validCursorKey checks the date (Unix milliseconds, MongoDB's precision) and the
// canonical, non-nil UUID every cursor ends with.
func validCursorKey(createdAt *int64, id *string) (time.Time, string, error) {
	if createdAt == nil || *createdAt <= 0 {
		return time.Time{}, "", fmt.Errorf("%w: creation date must be present and positive", ErrInvalidCursor)
	}
	if id == nil || len(*id) != canonicalIDLength {
		return time.Time{}, "", fmt.Errorf("%w: id must be a canonical uuid", ErrInvalidCursor)
	}
	parsed, err := uuid.Parse(*id)
	if err != nil || parsed == uuid.Nil || parsed.String() != *id {
		return time.Time{}, "", fmt.Errorf("%w: id must be a canonical uuid", ErrInvalidCursor)
	}

	return time.UnixMilli(*createdAt).UTC(), *id, nil
}
