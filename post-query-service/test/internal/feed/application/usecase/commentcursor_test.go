package usecase_test

import (
	"encoding/base64"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func TestCommentsCursor_RoundTripsTheFullSortKey(t *testing.T) {
	want := usecase.CommentsCursor{LikeCount: 5, ReplyCount: 3, CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 123_000_000, time.UTC), CommentID: uuid.NewString()}

	raw := usecase.EncodeCommentsCursor(want)
	got, err := usecase.DecodeCommentsCursor(raw)

	if err != nil {
		t.Fatalf("DecodeCommentsCursor(%q) error = %v", raw, err)
	}
	if got != want {
		t.Fatalf("DecodeCommentsCursor() = %+v, want %+v", got, want)
	}
	if _, err := base64.RawURLEncoding.Strict().DecodeString(raw); err != nil {
		t.Fatalf("cursor %q is not unpadded base64url: %v", raw, err)
	}
}

func TestRepliesCursor_RoundTripsTheSortKey(t *testing.T) {
	want := usecase.RepliesCursor{CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC), CommentID: uuid.NewString()}

	got, err := usecase.DecodeRepliesCursor(usecase.EncodeRepliesCursor(want))

	if err != nil || got != want {
		t.Fatalf("DecodeRepliesCursor() = %+v, %v, want %+v", got, err, want)
	}
}

func TestCommentsCursor_KeepsMillisecondsTheWayMongoDBStoresThem(t *testing.T) {
	stored := time.Date(2026, 3, 1, 10, 0, 0, 456_000_000, time.UTC)
	cursor := usecase.CommentsCursorOf(usecase.ContentComment{CommentID: uuid.NewString(), CreatedAt: stored})

	got, err := usecase.DecodeCommentsCursor(usecase.EncodeCommentsCursor(cursor))

	if err != nil || !got.CreatedAt.Equal(stored) {
		t.Fatalf("createdAt = %v, %v, want %v exactly", got.CreatedAt, err, stored)
	}
}

func encodeRaw(json string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(json))
}

func TestDecodeCommentsCursor_RejectsGarbage(t *testing.T) {
	id := uuid.NewString()
	cases := map[string]string{
		"empty":                 "",
		"not base64url":         "a+b/c==",
		"padded base64url":      base64.URLEncoding.EncodeToString([]byte(`{"l":1,"r":0,"t":1,"i":"` + id + `"}`)),
		"not json":              encodeRaw("hello"),
		"a json array":          encodeRaw(`[1,0,1]`),
		"missing like count":    encodeRaw(`{"r":0,"t":1772359200000,"i":"` + id + `"}`),
		"missing reply count":   encodeRaw(`{"l":1,"t":1772359200000,"i":"` + id + `"}`),
		"missing date":          encodeRaw(`{"l":1,"r":0,"i":"` + id + `"}`),
		"missing comment id":    encodeRaw(`{"l":1,"r":0,"t":1772359200000}`),
		"negative like count":   encodeRaw(`{"l":-1,"r":0,"t":1772359200000,"i":"` + id + `"}`),
		"negative reply count":  encodeRaw(`{"l":1,"r":-2,"t":1772359200000,"i":"` + id + `"}`),
		"zero date":             encodeRaw(`{"l":1,"r":0,"t":0,"i":"` + id + `"}`),
		"a string count":        encodeRaw(`{"l":"1","r":0,"t":1772359200000,"i":"` + id + `"}`),
		"comment id not a uuid": encodeRaw(`{"l":1,"r":0,"t":1772359200000,"i":"not-a-uuid"}`),
		"uppercase comment id":  encodeRaw(`{"l":1,"r":0,"t":1772359200000,"i":"` + "A" + id[1:] + `"}`),
		"nil comment id":        encodeRaw(`{"l":1,"r":0,"t":1772359200000,"i":"` + uuid.Nil.String() + `"}`),
		"unknown field":         encodeRaw(`{"l":1,"r":0,"t":1772359200000,"i":"` + id + `","x":1}`),
		"trailing data":         encodeRaw(`{"l":1,"r":0,"t":1772359200000,"i":"` + id + `"}{}`),
		"a replies cursor":      usecase.EncodeRepliesCursor(usecase.RepliesCursor{CreatedAt: time.Now(), CommentID: id}),
		"oversized":             string(make([]byte, 300)),
	}

	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := usecase.DecodeCommentsCursor(raw); !errors.Is(err, usecase.ErrInvalidCursor) {
				t.Fatalf("DecodeCommentsCursor(%q) error = %v, want ErrInvalidCursor", raw, err)
			}
		})
	}
}

func TestDecodeRepliesCursor_RejectsACommentsCursorAndGarbage(t *testing.T) {
	id := uuid.NewString()
	cases := map[string]string{
		"a comments cursor":  usecase.EncodeCommentsCursor(usecase.CommentsCursor{CreatedAt: time.Now(), CommentID: id}),
		"missing comment id": encodeRaw(`{"t":1772359200000}`),
		"missing date":       encodeRaw(`{"i":"` + id + `"}`),
		"not base64url":      "%%%",
	}

	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := usecase.DecodeRepliesCursor(raw); !errors.Is(err, usecase.ErrInvalidCursor) {
				t.Fatalf("DecodeRepliesCursor(%q) error = %v, want ErrInvalidCursor", raw, err)
			}
		})
	}
}
