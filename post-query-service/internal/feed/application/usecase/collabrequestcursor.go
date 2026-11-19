package usecase

import "time"

// CollabRequestsCursor is the sort key of the last row of a collab requests page: the
// request's date and the requester's id, both ascending. Neither changes once the
// request exists (the projection never rewrites requested_at), so the cursor is stable
// while statuses move.
type CollabRequestsCursor struct {
	RequestedAt time.Time
	UserID      string
}

// collabRequestsCursorWire is the JSON inside a collab requests cursor. Its r and u
// fields (no other list uses them) make a grid or comment cursor invalid here and the
// other way round.
type collabRequestsCursorWire struct {
	RequestedAt *int64  `json:"r"`
	UserID      *string `json:"u"`
}

// EncodeCollabRequestsCursor makes the opaque cursor a client sends back: base64url, no
// padding, of the sort key as JSON, the date in whole milliseconds (MongoDB's
// precision).
func EncodeCollabRequestsCursor(cursor CollabRequestsCursor) string {
	requestedAt := cursor.RequestedAt.UnixMilli()
	return encodeCursor(collabRequestsCursorWire{RequestedAt: &requestedAt, UserID: &cursor.UserID})
}

// DecodeCollabRequestsCursor reads a cursor made by EncodeCollabRequestsCursor, or
// returns ErrInvalidCursor.
func DecodeCollabRequestsCursor(raw string) (CollabRequestsCursor, error) {
	var wire collabRequestsCursorWire
	if err := decodeCursor(raw, &wire); err != nil {
		return CollabRequestsCursor{}, err
	}
	requestedAt, userID, err := validCursorKey(wire.RequestedAt, wire.UserID)
	if err != nil {
		return CollabRequestsCursor{}, err
	}

	return CollabRequestsCursor{RequestedAt: requestedAt, UserID: userID}, nil
}
