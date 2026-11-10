package usecase_test

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/share/application/usecase"
)

func TestDeleteShareUsecase_Execute_WarnsWithTheCorrelationAndUserWhenItDiscardsAnEvent(t *testing.T) {
	cases := map[string]struct {
		repository *fakeDeleteShareRepository
		stats      *fakeDeleteSharePostStats
		message    string
	}{
		"the post is gone": {&fakeDeleteShareRepository{removed: true}, &fakeDeleteSharePostStats{notFound: true}, "share removed for a post that is no longer projected"},
		"nothing matched":  {&fakeDeleteShareRepository{removed: false}, &fakeDeleteSharePostStats{}, "share delete event matched no document"},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			logs := &bytes.Buffer{}
			uc := usecase.NewDeleteShare(tc.repository, tc.stats, slog.New(slog.NewTextHandler(logs, nil)))
			input := usecase.DeleteShareInput{PostID: uuid.New(), UserID: uuid.New(), CorrelationID: uuid.New()}

			if err := uc.Execute(context.Background(), input); err != nil {
				t.Fatalf("Execute() error = %v, want an ack", err)
			}

			wants := []string{"level=WARN", tc.message, "correlation_id=" + input.CorrelationID.String(), "userId=" + input.UserID.String(), "post_id=" + input.PostID.String()}
			for _, want := range wants {
				if !strings.Contains(logs.String(), want) {
					t.Fatalf("log misses %q: %s", want, logs)
				}
			}
		})
	}
}
