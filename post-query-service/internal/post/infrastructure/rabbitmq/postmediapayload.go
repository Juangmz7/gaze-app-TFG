package rabbitmq

import (
	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
)

// PostMediaPayload is the wire shape of the Java PostMediaEventPayload record,
// shared by PostCreatedEvent and PostUpdatedEvent. ThumbnailURL can be null and
// Duration is omitted for an IMAGE.
type PostMediaPayload struct {
	ID           uuid.UUID `json:"id"`
	URL          string    `json:"url"`
	ThumbnailURL *string   `json:"thumbnailUrl"`
	MediaType    string    `json:"mediaType"`
	Duration     *int64    `json:"duration"`
	TaggedUsers  []string  `json:"taggedUsers"`
	Order        int       `json:"order"`
}

// toPostMedia maps the wire media to the usecase's type, keeping a nil list nil
// so an event without a media field stays distinguishable from an empty one.
func toPostMedia(payload []PostMediaPayload) []usecase.PostMedia {
	if payload == nil {
		return nil
	}

	media := make([]usecase.PostMedia, 0, len(payload))
	for _, element := range payload {
		media = append(media, usecase.PostMedia{
			ID:           element.ID,
			URL:          element.URL,
			ThumbnailURL: element.ThumbnailURL,
			MediaType:    element.MediaType,
			Duration:     element.Duration,
			TaggedUsers:  element.TaggedUsers,
			Order:        element.Order,
		})
	}

	return media
}
