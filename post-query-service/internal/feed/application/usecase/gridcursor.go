package usecase

import "time"

// GridCursor is the sort key of the last row of a profile grid page: its creation date
// and post id, both descending. On the posts grid the date is the post's; on the
// reposts grid it is the share's. Neither changes, so the cursor is stable.
type GridCursor struct {
	CreatedAt time.Time
	PostID    string
}

// gridCursorWire is the JSON inside a grid cursor. Its p field (not the comment
// cursors' i) makes a comment list cursor invalid here and the other way round.
type gridCursorWire struct {
	CreatedAt *int64  `json:"t"`
	PostID    *string `json:"p"`
}

// EncodeGridCursor makes the opaque cursor a client sends back: base64url, no padding,
// of the sort key as JSON, the date in whole milliseconds (MongoDB's precision).
func EncodeGridCursor(cursor GridCursor) string {
	createdAt := cursor.CreatedAt.UnixMilli()
	return encodeCursor(gridCursorWire{CreatedAt: &createdAt, PostID: &cursor.PostID})
}

// DecodeGridCursor reads a cursor made by EncodeGridCursor, or returns ErrInvalidCursor.
func DecodeGridCursor(raw string) (GridCursor, error) {
	var wire gridCursorWire
	if err := decodeCursor(raw, &wire); err != nil {
		return GridCursor{}, err
	}
	createdAt, postID, err := validCursorKey(wire.CreatedAt, wire.PostID)
	if err != nil {
		return GridCursor{}, err
	}

	return GridCursor{CreatedAt: createdAt, PostID: postID}, nil
}
