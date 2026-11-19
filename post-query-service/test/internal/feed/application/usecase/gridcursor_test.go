package usecase_test

import (
	"encoding/base64"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func TestGridCursor_RoundTripsTheSortKeyToTheMillisecond(t *testing.T) {
	want := usecase.GridCursor{CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 456_000_000, time.UTC), PostID: uuid.NewString()}

	raw := usecase.EncodeGridCursor(want)
	got, err := usecase.DecodeGridCursor(raw)

	if err != nil || got != want {
		t.Fatalf("DecodeGridCursor(%q) = %+v, %v, want %+v", raw, got, err, want)
	}
	if _, err := base64.RawURLEncoding.Strict().DecodeString(raw); err != nil {
		t.Fatalf("cursor %q is not unpadded base64url: %v", raw, err)
	}
}

func TestDecodeGridCursor_RejectsGarbage(t *testing.T) {
	id := uuid.NewString()
	cases := map[string]string{
		"empty":             "",
		"not base64url":     "a+b/c==",
		"padded base64url":  base64.URLEncoding.EncodeToString([]byte(`{"t":1772359200000,"p":"` + id + `"}`)),
		"not json":          encodeRaw("hello"),
		"missing date":      encodeRaw(`{"p":"` + id + `"}`),
		"missing post id":   encodeRaw(`{"t":1772359200000}`),
		"zero date":         encodeRaw(`{"t":0,"p":"` + id + `"}`),
		"negative date":     encodeRaw(`{"t":-5,"p":"` + id + `"}`),
		"post id not uuid":  encodeRaw(`{"t":1772359200000,"p":"not-a-uuid"}`),
		"uppercase post id": encodeRaw(`{"t":1772359200000,"p":"` + "A" + id[1:] + `"}`),
		"nil post id":       encodeRaw(`{"t":1772359200000,"p":"` + uuid.Nil.String() + `"}`),
		"unknown field":     encodeRaw(`{"t":1772359200000,"p":"` + id + `","x":1}`),
		"trailing data":     encodeRaw(`{"t":1772359200000,"p":"` + id + `"}{}`),
		"a replies cursor":  usecase.EncodeRepliesCursor(usecase.RepliesCursor{CreatedAt: time.Now(), CommentID: id}),
		"a comments cursor": usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: id}),
		"oversized":         string(make([]byte, 300)),
	}

	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := usecase.DecodeGridCursor(raw); !errors.Is(err, usecase.ErrInvalidCursor) {
				t.Fatalf("DecodeGridCursor(%q) error = %v, want ErrInvalidCursor", raw, err)
			}
		})
	}
}

func TestDecodeRepliesCursor_RejectsAGridCursor(t *testing.T) {
	raw := usecase.EncodeGridCursor(usecase.GridCursor{CreatedAt: time.Now(), PostID: uuid.NewString()})

	if _, err := usecase.DecodeRepliesCursor(raw); !errors.Is(err, usecase.ErrInvalidCursor) {
		t.Fatalf("DecodeRepliesCursor(grid cursor) error = %v, want ErrInvalidCursor", err)
	}
}
