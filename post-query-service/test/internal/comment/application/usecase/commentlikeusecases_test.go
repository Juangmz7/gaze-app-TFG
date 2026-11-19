package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

// fakeCommentLikes is the comment_likes read model: a set of (comment, user) pairs.
type fakeCommentLikes struct {
	pairs map[[2]uuid.UUID]bool
	err   error
}

func newFakeCommentLikes() *fakeCommentLikes {
	return &fakeCommentLikes{pairs: map[[2]uuid.UUID]bool{}}
}

func (f *fakeCommentLikes) Insert(_ context.Context, input usecase.RecordCommentLikeInput) (bool, error) {
	key := [2]uuid.UUID{input.CommentID, input.UserID}
	if f.err != nil || f.pairs[key] {
		return false, f.err
	}
	f.pairs[key] = true
	return true, nil
}

func (f *fakeCommentLikes) Delete(_ context.Context, commentID, userID uuid.UUID) (bool, error) {
	key := [2]uuid.UUID{commentID, userID}
	if f.err != nil || !f.pairs[key] {
		return false, f.err
	}
	delete(f.pairs, key)
	return true, nil
}

// fakeCommentCounter is one comment's like counter, floored at 0 like the real one.
type fakeCommentCounter struct {
	likes    int64
	notFound bool
	err      error
}

func (f *fakeCommentCounter) AdjustLikeCount(_ context.Context, _ uuid.UUID, delta int64) (bool, error) {
	if f.err != nil || f.notFound {
		return false, f.err
	}
	f.likes = max(0, f.likes+delta)
	return true, nil
}

func TestRecordCommentLikeUsecase_Execute_CountsALikeDeliveredTwiceOnce(t *testing.T) {
	likes, counter := newFakeCommentLikes(), &fakeCommentCounter{}
	uc := usecase.NewRecordCommentLike(likes, counter)
	input := usecase.RecordCommentLikeInput{CommentID: uuid.New(), PostID: uuid.New(), UserID: uuid.New(), CreatedAt: time.Now().UTC()}

	for range 2 {
		if err := uc.Execute(context.Background(), input); err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
	}

	if counter.likes != 1 {
		t.Fatalf("like_count = %d, want 1", counter.likes)
	}
}

func TestRecordCommentLikeUsecase_Execute_ReturnsARetryableErrorWhenTheCommentIsNotProjectedYet(t *testing.T) {
	uc := usecase.NewRecordCommentLike(newFakeCommentLikes(), &fakeCommentCounter{notFound: true})

	err := uc.Execute(context.Background(), usecase.RecordCommentLikeInput{CommentID: uuid.New(), UserID: uuid.New()})

	if err == nil || !strings.Contains(err.Error(), "no projection yet") {
		t.Fatalf("Execute() error = %v, want a retryable error that rolls the like back", err)
	}
}

func TestRecordCommentLikeUsecase_Execute_RejectsMissingIDsAndPropagatesFailures(t *testing.T) {
	uc := usecase.NewRecordCommentLike(newFakeCommentLikes(), &fakeCommentCounter{})
	for name, input := range map[string]usecase.RecordCommentLikeInput{
		"no comment": {UserID: uuid.New()},
		"no user":    {CommentID: uuid.New()},
	} {
		t.Run(name, func(t *testing.T) {
			if err := uc.Execute(context.Background(), input); err == nil {
				t.Fatal("Execute() error = nil")
			}
		})
	}

	wantErr := errors.New("mongo down")
	input := usecase.RecordCommentLikeInput{CommentID: uuid.New(), UserID: uuid.New()}
	if err := usecase.NewRecordCommentLike(&fakeCommentLikes{pairs: map[[2]uuid.UUID]bool{}, err: wantErr}, &fakeCommentCounter{}).Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want the insert failure", err)
	}
	if err := usecase.NewRecordCommentLike(newFakeCommentLikes(), &fakeCommentCounter{err: wantErr}).Execute(context.Background(), input); !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want the counter failure", err)
	}
}

func TestDeleteCommentLikeUsecase_Execute_UncountsOnceAndNeverGoesBelowZero(t *testing.T) {
	likes, counter := newFakeCommentLikes(), &fakeCommentCounter{}
	commentID, userID := uuid.New(), uuid.New()
	if err := usecase.NewRecordCommentLike(likes, counter).Execute(context.Background(), usecase.RecordCommentLikeInput{CommentID: commentID, UserID: userID}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	uc := usecase.NewDeleteCommentLike(likes, counter, testDeleteCommentLogger())

	// Delivered twice, plus an unlike of a like that never existed.
	for _, input := range []usecase.DeleteCommentLikeInput{
		{CommentID: commentID, UserID: userID},
		{CommentID: commentID, UserID: userID},
		{CommentID: commentID, UserID: uuid.New()},
	} {
		if err := uc.Execute(context.Background(), input); err != nil {
			t.Fatalf("Execute() error = %v", err)
		}
	}

	if counter.likes != 0 {
		t.Fatalf("like_count = %d, want 0", counter.likes)
	}
}

func TestDeleteCommentLikeUsecase_Execute_WarnsAndAcksWhenNothingMatchedOrTheCommentIsGone(t *testing.T) {
	cases := map[string]struct {
		seed    bool
		counter *fakeCommentCounter
		message string
	}{
		"nothing matched":     {false, &fakeCommentCounter{}, "comment like delete event matched no document"},
		"the comment is gone": {true, &fakeCommentCounter{notFound: true}, "comment like removed for a comment that is no longer projected"},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			likes := newFakeCommentLikes()
			input := usecase.DeleteCommentLikeInput{CommentID: uuid.New(), UserID: uuid.New(), CorrelationID: uuid.New()}
			if tc.seed {
				likes.pairs[[2]uuid.UUID{input.CommentID, input.UserID}] = true
			}
			logs := &bytes.Buffer{}

			if err := usecase.NewDeleteCommentLike(likes, tc.counter, slog.New(slog.NewTextHandler(logs, nil))).Execute(context.Background(), input); err != nil {
				t.Fatalf("Execute() error = %v, want an ack", err)
			}
			for _, want := range []string{"level=WARN", tc.message, "correlation_id=" + input.CorrelationID.String(), "userId=" + input.UserID.String(), "comment_id=" + input.CommentID.String()} {
				if !strings.Contains(logs.String(), want) {
					t.Fatalf("log misses %q: %s", want, logs)
				}
			}
		})
	}
}
