package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
)

func TestRecordCommentUsecase_Execute_IncrementsTheParentsReplyCountForANewReply(t *testing.T) {
	repository := &fakeRecordCommentRepository{inserted: true}
	input := validRecordCommentInput()
	input.ReplyTo = uuid.New()

	if err := usecase.NewRecordComment(repository, &fakeRecordCommentPostStats{}, testRecordCommentLogger()).Execute(context.Background(), input); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repository.replyDeltas) != 1 || repository.replyDeltas[0] != 1 || repository.parentIDs[0] != input.ReplyTo {
		t.Fatalf("AdjustReplyCount() calls = %v on %v, want +1 on the parent", repository.replyDeltas, repository.parentIDs)
	}
}

func TestRecordCommentUsecase_Execute_LeavesReplyCountsAloneForATopLevelCommentOrARedelivery(t *testing.T) {
	topLevel := &fakeRecordCommentRepository{inserted: true}
	if err := usecase.NewRecordComment(topLevel, &fakeRecordCommentPostStats{}, testRecordCommentLogger()).Execute(context.Background(), validRecordCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	redelivered := &fakeRecordCommentRepository{inserted: false}
	reply := validRecordCommentInput()
	reply.ReplyTo = uuid.New()
	if err := usecase.NewRecordComment(redelivered, &fakeRecordCommentPostStats{}, testRecordCommentLogger()).Execute(context.Background(), reply); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(topLevel.replyDeltas)+len(redelivered.replyDeltas) != 0 {
		t.Fatalf("AdjustReplyCount() calls = %v and %v, want none", topLevel.replyDeltas, redelivered.replyDeltas)
	}
}

func TestRecordCommentUsecase_Execute_KeepsAReplyToAParentThatIsNotProjectedAndWarns(t *testing.T) {
	logs := &bytes.Buffer{}
	input := validRecordCommentInput()
	input.ReplyTo, input.CorrelationID = uuid.New(), uuid.New()

	err := usecase.NewRecordComment(&fakeRecordCommentRepository{inserted: true, parentNotFound: true}, &fakeRecordCommentPostStats{}, slog.New(slog.NewTextHandler(logs, nil))).Execute(context.Background(), input)

	if err != nil {
		t.Fatalf("Execute() error = %v, want an ack: a reply to a deleted comment is valid", err)
	}
	for _, want := range []string{"level=WARN", "correlation_id=" + input.CorrelationID.String(), "reply_to=" + input.ReplyTo.String()} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log misses %q: %s", want, logs)
		}
	}
}

func TestRecordCommentUsecase_Execute_ReturnsTheReplyCountFailure(t *testing.T) {
	wantErr := errors.New("mongo down")
	input := validRecordCommentInput()
	input.ReplyTo = uuid.New()

	err := usecase.NewRecordComment(&fakeRecordCommentRepository{inserted: true, adjustErr: wantErr}, &fakeRecordCommentPostStats{}, testRecordCommentLogger()).Execute(context.Background(), input)

	if !errors.Is(err, wantErr) {
		t.Fatalf("Execute() error = %v, want %v so the transaction aborts", err, wantErr)
	}
}

func TestDeleteCommentUsecase_Execute_DecrementsTheParentsReplyCountWhenAReplyWasRemoved(t *testing.T) {
	parentID := uuid.New()
	repository := &fakeDeleteCommentRepository{removed: true, replyTo: parentID}

	if err := usecase.NewDeleteComment(repository, &fakeDeleteCommentPostStats{}, testDeleteCommentLogger()).Execute(context.Background(), validDeleteCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	if len(repository.replyDeltas) != 1 || repository.replyDeltas[0] != -1 || repository.parentIDs[0] != parentID {
		t.Fatalf("AdjustReplyCount() calls = %v on %v, want -1 on the parent", repository.replyDeltas, repository.parentIDs)
	}
}

func TestDeleteCommentUsecase_Execute_LeavesReplyCountsAloneWhenNothingOrATopLevelCommentWasRemoved(t *testing.T) {
	for name, repository := range map[string]*fakeDeleteCommentRepository{
		"nothing removed":     {removed: false, replyTo: uuid.Nil},
		"a top-level comment": {removed: true, replyTo: uuid.Nil},
	} {
		t.Run(name, func(t *testing.T) {
			if err := usecase.NewDeleteComment(repository, &fakeDeleteCommentPostStats{}, testDeleteCommentLogger()).Execute(context.Background(), validDeleteCommentInput()); err != nil {
				t.Fatalf("Execute() error = %v", err)
			}
			if len(repository.replyDeltas) != 0 {
				t.Fatalf("AdjustReplyCount() calls = %v, want none", repository.replyDeltas)
			}
		})
	}
}

func TestDeleteCommentUsecase_Execute_WarnsAndAcksWhenTheParentIsGone(t *testing.T) {
	logs := &bytes.Buffer{}
	repository := &fakeDeleteCommentRepository{removed: true, replyTo: uuid.New(), parentNotFound: true}

	if err := usecase.NewDeleteComment(repository, &fakeDeleteCommentPostStats{}, slog.New(slog.NewTextHandler(logs, nil))).Execute(context.Background(), validDeleteCommentInput()); err != nil {
		t.Fatalf("Execute() error = %v, want an ack", err)
	}
	if !strings.Contains(logs.String(), "reply removed for a parent comment that is no longer projected") {
		t.Fatalf("log = %s", logs)
	}
}
