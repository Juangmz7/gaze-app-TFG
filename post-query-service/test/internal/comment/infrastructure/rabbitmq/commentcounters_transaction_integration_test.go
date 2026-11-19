package rabbitmq_test

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/application/usecase"
	commentmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/comment/infrastructure/rabbitmq"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	postmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
)

// commentHandlers are the real comment handlers on a real replica set, the way
// bootstrap wires them.
type commentHandlers struct {
	db          *mongodriver.Database
	postID      uuid.UUID
	created     *rabbitmq.PostCommentCreatedHandler
	deleted     *rabbitmq.CommentDeletedHandler
	likeCreated *rabbitmq.PostCommentLikeCreatedHandler
	likeDeleted *rabbitmq.PostCommentLikeDeletedHandler
}

func newCommentHandlers(t *testing.T, ctx context.Context, db *mongodriver.Database) *commentHandlers {
	t.Helper()

	marks := idempotency.NewRepository(db)
	comments := commentmongo.NewRepository(db)
	likes := commentmongo.NewCommentLikeRepository(db)
	posts := postmongo.NewRepository(db)
	for _, ensure := range []func(context.Context) error{marks.EnsureIndexes, comments.EnsureIndexes, likes.EnsureIndexes, posts.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}
	postID := uuid.New()
	if err := posts.Insert(ctx, postusecase.CreatePostInput{PostID: postID, UserID: uuid.New(), PostType: "BASIC"}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}

	transactor := database.NewTransactor(db.Client())
	logger := discardCommentLikeLogger()
	return &commentHandlers{
		db:          db,
		postID:      postID,
		created:     rabbitmq.NewPostCommentCreatedHandler(transactor, marks, usecase.NewRecordComment(comments, posts, logger), logger),
		deleted:     rabbitmq.NewCommentDeletedHandler(transactor, marks, usecase.NewDeleteComment(comments, posts, logger), logger),
		likeCreated: rabbitmq.NewPostCommentLikeCreatedHandler(transactor, marks, usecase.NewRecordCommentLike(likes, comments), logger),
		likeDeleted: rabbitmq.NewPostCommentLikeDeletedHandler(transactor, marks, usecase.NewDeleteCommentLike(likes, comments, logger), logger),
	}
}

func (h *commentHandlers) createComment(t *testing.T, ctx context.Context, replyTo *uuid.UUID) (uuid.UUID, string) {
	t.Helper()

	commentID := uuid.New()
	reply := "null"
	if replyTo != nil {
		reply = `"` + replyTo.String() + `"`
	}
	payload := fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:30Z","commentId":"%s","postId":"%s","userId":"%s","content":"Lovely light","replyTo":%s,"createdAt":"2026-03-01T10:15:00Z","updatedAt":"2026-03-01T10:15:00Z"}`,
		uuid.NewString(), uuid.NewString(), commentID, h.postID, uuid.NewString(), reply)
	if err := h.created.Handle(ctx, deliver(payload)); err != nil {
		t.Fatalf("created Handle() error = %v", err)
	}
	return commentID, payload
}

func (h *commentHandlers) deleteComment(t *testing.T, ctx context.Context, commentID uuid.UUID) string {
	t.Helper()

	payload := fmt.Sprintf(`{"id":"%s","correlationId":"%s","commentId":"%s","postId":"%s","userId":"%s","occurredAt":"2026-03-01T10:20:00Z"}`,
		uuid.NewString(), uuid.NewString(), commentID, h.postID, uuid.NewString())
	if err := h.deleted.Handle(ctx, deliver(payload)); err != nil {
		t.Fatalf("deleted Handle() error = %v", err)
	}
	return payload
}

func (h *commentHandlers) counters(t *testing.T, ctx context.Context, commentID uuid.UUID) (int64, int64) {
	t.Helper()

	var doc struct {
		LikeCount  int64 `bson:"like_count"`
		ReplyCount int64 `bson:"reply_count"`
	}
	if err := h.db.Collection("post_comments").FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}}).Decode(&doc); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	return doc.LikeCount, doc.ReplyCount
}

func likeCreatedPayload(commentID, userID uuid.UUID) string {
	return fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:30Z","postId":"%s","commentId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":1,"createdAt":"2026-03-01T10:15:30Z"}`,
		uuid.NewString(), uuid.NewString(), uuid.NewString(), commentID, userID)
}

func likeDeletedPayload(commentID, userID uuid.UUID) string {
	return fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:16:30Z","commentId":"%s","userId":"%s","source":"HOME_FEED","feedPosition":1}`,
		uuid.NewString(), uuid.NewString(), commentID, userID)
}

// TestCommentCounters_AgainstARealReplicaSet shares one container between its subtests.
func TestCommentCounters_AgainstARealReplicaSet(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	db := startCommentTransactionalMongo(t, ctx)

	t.Run("a like event delivered twice, even concurrently, counts once", func(t *testing.T) {
		handlers := newCommentHandlers(t, ctx, db)
		commentID, _ := handlers.createComment(t, ctx, nil)
		payload := likeCreatedPayload(commentID, uuid.New())

		var group sync.WaitGroup
		failures := make(chan error, 6)
		for range 6 {
			group.Add(1)
			go func() {
				defer group.Done()
				var err error
				// Retried like the retry middleware does after a write conflict.
				for range 5 {
					if err = handlers.likeCreated.Handle(ctx, deliver(payload)); err == nil {
						return
					}
				}
				failures <- err
			}()
		}
		group.Wait()
		close(failures)
		for err := range failures {
			t.Fatalf("Handle() error after retries = %v", err)
		}

		if likes, _ := handlers.counters(t, ctx, commentID); likes != 1 {
			t.Fatalf("like_count = %d, want 1", likes)
		}
		stored, err := db.Collection("comment_likes").CountDocuments(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}})
		if err != nil || stored != 1 {
			t.Fatalf("comment_likes = %d, %v, want 1", stored, err)
		}
	})

	t.Run("two events for the same like count once, and unlikes never go below zero", func(t *testing.T) {
		handlers := newCommentHandlers(t, ctx, db)
		commentID, _ := handlers.createComment(t, ctx, nil)
		userID := uuid.New()

		for _, payload := range []string{likeCreatedPayload(commentID, userID), likeCreatedPayload(commentID, userID), likeCreatedPayload(commentID, uuid.New())} {
			if err := handlers.likeCreated.Handle(ctx, deliver(payload)); err != nil {
				t.Fatalf("Handle() error = %v", err)
			}
		}
		if likes, _ := handlers.counters(t, ctx, commentID); likes != 2 {
			t.Fatalf("like_count = %d, want 2 (two users)", likes)
		}

		deleted := likeDeletedPayload(commentID, userID)
		for _, payload := range []string{deleted, deleted, likeDeletedPayload(commentID, userID), likeDeletedPayload(commentID, uuid.New())} {
			if err := handlers.likeDeleted.Handle(ctx, deliver(payload)); err != nil {
				t.Fatalf("Handle() error = %v", err)
			}
		}
		if likes, _ := handlers.counters(t, ctx, commentID); likes != 1 {
			t.Fatalf("like_count = %d, want 1 after one real unlike", likes)
		}
	})

	t.Run("a like for a comment not projected yet is retried and leaves nothing behind", func(t *testing.T) {
		handlers := newCommentHandlers(t, ctx, db)
		commentID := uuid.New()

		if err := handlers.likeCreated.Handle(ctx, deliver(likeCreatedPayload(commentID, uuid.New()))); err == nil {
			t.Fatal("Handle() error = nil, want a retryable error")
		}
		stored, err := db.Collection("comment_likes").CountDocuments(ctx, bson.D{{Key: "comment_id", Value: commentID.String()}})
		if err != nil || stored != 0 {
			t.Fatalf("comment_likes = %d, %v, want 0 after the rollback", stored, err)
		}
	})

	t.Run("replies created and deleted move the parent's reply count once", func(t *testing.T) {
		handlers := newCommentHandlers(t, ctx, db)
		parentID, _ := handlers.createComment(t, ctx, nil)
		first, firstPayload := handlers.createComment(t, ctx, &parentID)
		handlers.createComment(t, ctx, &parentID)
		// A redelivered creation counts nothing.
		if err := handlers.created.Handle(ctx, deliver(firstPayload)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
		if _, replies := handlers.counters(t, ctx, parentID); replies != 2 {
			t.Fatalf("reply_count = %d, want 2", replies)
		}

		deletedPayload := handlers.deleteComment(t, ctx, first)
		if err := handlers.deleted.Handle(ctx, deliver(deletedPayload)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}
		// A second delete event for the same, already removed, reply.
		handlers.deleteComment(t, ctx, first)
		if _, replies := handlers.counters(t, ctx, parentID); replies != 1 {
			t.Fatalf("reply_count = %d, want 1", replies)
		}
	})

	t.Run("a reply projected before its parent is counted when the parent arrives", func(t *testing.T) {
		handlers := newCommentHandlers(t, ctx, db)
		parentID := uuid.New()
		handlers.createComment(t, ctx, &parentID)

		parentPayload := fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:30Z","commentId":"%s","postId":"%s","userId":"%s","content":"Parent","replyTo":null,"createdAt":"2026-03-01T10:14:00Z","updatedAt":"2026-03-01T10:14:00Z"}`,
			uuid.NewString(), uuid.NewString(), parentID, handlers.postID, uuid.NewString())
		if err := handlers.created.Handle(ctx, deliver(parentPayload)); err != nil {
			t.Fatalf("Handle() error = %v", err)
		}

		if _, replies := handlers.counters(t, ctx, parentID); replies != 1 {
			t.Fatalf("reply_count = %d, want 1", replies)
		}
	})
}

// startCommentTransactionalMongo starts a single-node replica set, which transactions need.
func startCommentTransactionalMongo(t *testing.T, ctx context.Context) *mongodriver.Database {
	t.Helper()

	container, err := mongodb.Run(ctx, "mongo:7", mongodb.WithReplicaSet("rs0"))
	if err != nil {
		t.Fatalf("mongodb.Run() error = %v", err)
	}
	t.Cleanup(func() {
		if terminateErr := container.Terminate(context.Background()); terminateErr != nil {
			t.Logf("container.Terminate() error = %v", terminateErr)
		}
	})
	endpoint, err := container.PortEndpoint(ctx, "27017/tcp", "")
	if err != nil {
		t.Fatalf("container.PortEndpoint() error = %v", err)
	}
	client, err := mongodriver.Connect(options.Client().ApplyURI("mongodb://" + endpoint + "/?directConnection=true"))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v", err)
	}
	t.Cleanup(func() {
		if disconnectErr := client.Disconnect(context.Background()); disconnectErr != nil {
			t.Logf("client.Disconnect() error = %v", disconnectErr)
		}
	})

	return client.Database("post_query_comment_counters_test")
}
