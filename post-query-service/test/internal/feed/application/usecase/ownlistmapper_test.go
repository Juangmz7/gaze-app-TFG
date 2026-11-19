package usecase_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

func sampleTimedPage() usecase.TimedGridPage {
	cover, next := "https://media.test/cover.jpg", "next-page"
	return usecase.TimedGridPage{
		Items: []usecase.TimedGridItem{{
			GridItem: usecase.GridItem{
				PostID: "post-1", CoverThumbnailURL: &cover, MediaType: "VIDEO", MediaCount: 3, PostType: "BASIC",
				LikeCount: 7, CreatedAt: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
			},
			At: time.Date(2026, 3, 2, 8, 30, 0, 0, time.UTC),
		}},
		NextCursor: &next,
	}
}

// ownTabMappers are the two own tab mappers, each with the JSON name of the item's time.
var ownTabMappers = map[string]func(context.Context, usecase.TimedGridPage, usecase.MediaURLSigner) (any, error){
	"likedAt": func(ctx context.Context, page usecase.TimedGridPage, signer usecase.MediaURLSigner) (any, error) {
		return usecase.LikedPageToResponse(ctx, page, signer)
	},
	"viewedAt": func(ctx context.Context, page usecase.TimedGridPage, signer usecase.MediaURLSigner) (any, error) {
		return usecase.HistoryPageToResponse(ctx, page, signer)
	},
}

func TestOwnTabPageToResponse_IsAGridItemPlusTheRowTimeWithTheCoverSigned(t *testing.T) {
	for timeName, mapPage := range ownTabMappers {
		t.Run(timeName, func(t *testing.T) {
			response, err := mapPage(context.Background(), sampleTimedPage(), markingSigner{})
			if err != nil {
				t.Fatalf("map error = %v", err)
			}

			encoded, err := json.Marshal(response)
			if err != nil {
				t.Fatalf("Marshal() error = %v", err)
			}
			want := `{"items":[{"postId":"post-1","coverThumbnailUrl":"https://media.test/cover.jpg?sig=signed","mediaType":"VIDEO","mediaCount":3,"postType":"BASIC","collabStatus":null,"likeCount":7,"createdAt":"2026-03-01T10:00:00Z","` +
				timeName + `":"2026-03-02T08:30:00Z"}],"nextCursor":"next-page"}`
			if string(encoded) != want {
				t.Fatalf("JSON = %s\nwant   %s", encoded, want)
			}
		})
	}
}

func TestOwnTabPageToResponse_AnswersAnEmptyListAndANullCursor(t *testing.T) {
	for timeName, mapPage := range ownTabMappers {
		response, err := mapPage(context.Background(), usecase.TimedGridPage{}, markingSigner{})
		if err != nil {
			t.Fatalf("%s map error = %v", timeName, err)
		}
		encoded, _ := json.Marshal(response)
		if string(encoded) != `{"items":[],"nextCursor":null}` {
			t.Fatalf("%s JSON = %s, want an empty list and a null cursor", timeName, encoded)
		}
	}
}

func TestOwnTabPageToResponse_ReturnsTheSignerErrorAndRequiresASigner(t *testing.T) {
	failure := errors.New("no delegation key")
	for timeName, mapPage := range ownTabMappers {
		if _, err := mapPage(context.Background(), sampleTimedPage(), markingSigner{err: failure}); !errors.Is(err, failure) {
			t.Fatalf("%s error = %v, want the signer's", timeName, err)
		}
		if _, err := mapPage(context.Background(), sampleTimedPage(), nil); err == nil {
			t.Fatalf("%s error = nil without a signer", timeName)
		}
	}
}
