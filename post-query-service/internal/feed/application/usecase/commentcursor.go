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

// ErrInvalidCommentCursor is returned for a cursor that was not produced by this
// service for that list: not base64url, not the expected JSON, a missing or unknown
// field, or a value out of range.
var ErrInvalidCommentCursor = errors.New("invalid comment cursor")

// maxCommentCursorLength bounds what a client can make the service decode. A real
// cursor is about 120 characters.
const maxCommentCursorLength = 256

// canonicalCommentIDLength is the length of the only form a comment id is accepted in.
const canonicalCommentIDLength = 36

// CommentsCursor is the full sort key of the last top-level comment of a page: like
// count, reply count, creation date and comment id, the order of the list. The next
// page starts strictly after it.
//
// The counters are mutable, so a comment whose likes or replies change between two
// requests can move across the cursor and show up twice or not at all. That is
// accepted: the cursor stays the full sort tuple, so every page is still an index
// seek, and the client dedupes by commentId.
type CommentsCursor struct {
	LikeCount  int64
	ReplyCount int64
	CreatedAt  time.Time
	CommentID  string
}

// RepliesCursor is the sort key of the last reply of a page: creation date and
// comment id. Replies never change position, so it is stable.
type RepliesCursor struct {
	CreatedAt time.Time
	CommentID string
}

// commentsCursorWire is the JSON inside a comments cursor. Pointers tell a missing
// field from a zero. The date is whole milliseconds, the precision MongoDB stores, so
// a cursor equals the stored value exactly.
type commentsCursorWire struct {
	LikeCount  *int64  `json:"l"`
	ReplyCount *int64  `json:"r"`
	CreatedAt  *int64  `json:"t"`
	CommentID  *string `json:"i"`
}

// repliesCursorWire is the JSON inside a replies cursor. It has no l and r, so a
// comments cursor is rejected by the replies list and the other way round.
type repliesCursorWire struct {
	CreatedAt *int64  `json:"t"`
	CommentID *string `json:"i"`
}

// CommentsCursorOf is the cursor that resumes the list after comment.
func CommentsCursorOf(comment ContentComment) CommentsCursor {
	return CommentsCursor{
		LikeCount:  comment.LikeCount,
		ReplyCount: comment.ReplyCount,
		CreatedAt:  comment.CreatedAt,
		CommentID:  comment.CommentID,
	}
}

// RepliesCursorOf is the cursor that resumes the replies after reply.
func RepliesCursorOf(reply ContentComment) RepliesCursor {
	return RepliesCursor{CreatedAt: reply.CreatedAt, CommentID: reply.CommentID}
}

// EncodeCommentsCursor makes the opaque cursor a client sends back: base64url, no
// padding, of the sort key as JSON.
func EncodeCommentsCursor(cursor CommentsCursor) string {
	createdAt := cursor.CreatedAt.UnixMilli()
	return encodeCursor(commentsCursorWire{
		LikeCount:  &cursor.LikeCount,
		ReplyCount: &cursor.ReplyCount,
		CreatedAt:  &createdAt,
		CommentID:  &cursor.CommentID,
	})
}

// DecodeCommentsCursor reads a cursor made by EncodeCommentsCursor, or returns
// ErrInvalidCommentCursor.
func DecodeCommentsCursor(raw string) (CommentsCursor, error) {
	var wire commentsCursorWire
	if err := decodeCursor(raw, &wire); err != nil {
		return CommentsCursor{}, err
	}
	if wire.LikeCount == nil || wire.ReplyCount == nil || *wire.LikeCount < 0 || *wire.ReplyCount < 0 {
		return CommentsCursor{}, fmt.Errorf("%w: counters must be present and not negative", ErrInvalidCommentCursor)
	}
	createdAt, commentID, err := validCursorKey(wire.CreatedAt, wire.CommentID)
	if err != nil {
		return CommentsCursor{}, err
	}

	return CommentsCursor{LikeCount: *wire.LikeCount, ReplyCount: *wire.ReplyCount, CreatedAt: createdAt, CommentID: commentID}, nil
}

// EncodeRepliesCursor makes the opaque cursor of the replies list.
func EncodeRepliesCursor(cursor RepliesCursor) string {
	createdAt := cursor.CreatedAt.UnixMilli()
	return encodeCursor(repliesCursorWire{CreatedAt: &createdAt, CommentID: &cursor.CommentID})
}

// DecodeRepliesCursor reads a cursor made by EncodeRepliesCursor, or returns
// ErrInvalidCommentCursor.
func DecodeRepliesCursor(raw string) (RepliesCursor, error) {
	var wire repliesCursorWire
	if err := decodeCursor(raw, &wire); err != nil {
		return RepliesCursor{}, err
	}
	createdAt, commentID, err := validCursorKey(wire.CreatedAt, wire.CommentID)
	if err != nil {
		return RepliesCursor{}, err
	}

	return RepliesCursor{CreatedAt: createdAt, CommentID: commentID}, nil
}

func encodeCursor(wire any) string {
	// Marshalling a struct of pointers to numbers and strings cannot fail.
	payload, _ := json.Marshal(wire)
	return base64.RawURLEncoding.EncodeToString(payload)
}

// decodeCursor decodes base64url without padding into exactly one JSON object with
// no unknown field.
func decodeCursor(raw string, wire any) error {
	if raw == "" || len(raw) > maxCommentCursorLength {
		return fmt.Errorf("%w: empty or longer than %d characters", ErrInvalidCommentCursor, maxCommentCursorLength)
	}
	payload, err := base64.RawURLEncoding.Strict().DecodeString(raw)
	if err != nil {
		return fmt.Errorf("%w: not base64url: %v", ErrInvalidCommentCursor, err)
	}

	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(wire); err != nil {
		return fmt.Errorf("%w: not the expected JSON: %v", ErrInvalidCommentCursor, err)
	}
	if decoder.More() {
		return fmt.Errorf("%w: trailing data", ErrInvalidCommentCursor)
	}

	return nil
}

// validCursorKey checks the date and comment id every cursor ends with.
func validCursorKey(createdAt *int64, commentID *string) (time.Time, string, error) {
	if createdAt == nil || *createdAt <= 0 {
		return time.Time{}, "", fmt.Errorf("%w: creation date must be present and positive", ErrInvalidCommentCursor)
	}
	if commentID == nil || len(*commentID) != canonicalCommentIDLength {
		return time.Time{}, "", fmt.Errorf("%w: comment id must be a canonical uuid", ErrInvalidCommentCursor)
	}
	parsed, err := uuid.Parse(*commentID)
	if err != nil || parsed == uuid.Nil || parsed.String() != *commentID {
		return time.Time{}, "", fmt.Errorf("%w: comment id must be a canonical uuid", ErrInvalidCommentCursor)
	}

	return time.UnixMilli(*createdAt).UTC(), *commentID, nil
}
