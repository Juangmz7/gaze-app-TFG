package usecase_test

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

func TestDeleteCommentUsecase_Execute_WarnsWithTheCorrelationAndUserWhenItDiscardsAnEvent(t *testing.T) {
	cases := map[string]struct {
		repository *fakeDeleteCommentRepository
		stats      *fakeDeleteCommentPostStats
		message    string
	}{
		"the post is gone": {&fakeDeleteCommentRepository{removed: true}, &fakeDeleteCommentPostStats{notFound: true}, "comment removed for a post that is no longer projected"},
		"nothing matched":  {&fakeDeleteCommentRepository{removed: false}, &fakeDeleteCommentPostStats{}, "comment delete event matched no document"},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			logs := &bytes.Buffer{}
			uc := usecase.NewDeleteComment(tc.repository, tc.stats, slog.New(slog.NewTextHandler(logs, nil)))
			input := usecase.DeleteCommentInput{PostID: uuid.New(), CommentID: uuid.New(), UserID: uuid.New(), CorrelationID: uuid.New()}

			if err := uc.Execute(context.Background(), input); err != nil {
				t.Fatalf("Execute() error = %v, want an ack", err)
			}

			wants := []string{"level=WARN", tc.message, "correlation_id=" + input.CorrelationID.String(), "userId=" + input.UserID.String(), "post_id=" + input.PostID.String(), "comment_id=" + input.CommentID.String()}
			for _, want := range wants {
				if !strings.Contains(logs.String(), want) {
					t.Fatalf("log misses %q: %s", want, logs)
				}
			}
		})
	}
}
