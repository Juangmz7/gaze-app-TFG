package usecase

import (
	"fmt"
	"strings"

	"github.com/google/uuid"
)

// Media types post-command-service publishes.
const (
	PostMediaTypeImage = "IMAGE"
	PostMediaTypeVideo = "VIDEO"
)

// PostMedia is one media element of a post. ThumbnailURL can be null. Duration
// is null for an IMAGE and holds milliseconds rounded to the second for a VIDEO:
// post-command-service already publishes it in that unit, so it is never
// re-scaled or re-rounded. Order is 1-based.
type PostMedia struct {
	ID           uuid.UUID
	URL          string
	ThumbnailURL *string
	MediaType    string
	Duration     *int64
	TaggedUsers  []string
	Order        int
}

// ValidatePostMedia checks the rules every stored media element must meet.
func ValidatePostMedia(media []PostMedia) error {
	for index, element := range media {
		if element.ID == uuid.Nil {
			return fmt.Errorf("media %d: id is required", index)
		}
		if strings.TrimSpace(element.URL) == "" {
			return fmt.Errorf("media %d: url is required", index)
		}
		if element.MediaType != PostMediaTypeImage && element.MediaType != PostMediaTypeVideo {
			return fmt.Errorf("media %d: unknown media type %q", index, element.MediaType)
		}
		if element.Order < 1 {
			return fmt.Errorf("media %d: order %d is not 1-based", index, element.Order)
		}
		if element.Duration != nil && *element.Duration < 0 {
			return fmt.Errorf("media %d: duration %d is negative", index, *element.Duration)
		}
	}

	return nil
}
