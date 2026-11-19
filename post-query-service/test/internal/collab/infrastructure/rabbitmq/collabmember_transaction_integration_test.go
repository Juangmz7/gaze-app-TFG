package rabbitmq_test

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/ThreeDotsLabs/watermill/message"
	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/bson"
	mongodriver "go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
	collabmongo "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/mongo"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/infrastructure/rabbitmq"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/database"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/idempotency"
)

// Against a real replica set: every membership event of one user delivered at once and
// twice, in any order, retried the way the retry middleware does, must end in the
// newest state with one row.
func TestCollabMemberHandlers_ConcurrentOutOfOrderDeliveriesEndInTheNewestState(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db := startTransactionalCollabMongo(t, ctx)
	idempotencyRepository := idempotency.NewRepository(db)
	members := collabmongo.NewMemberRepository(db)
	for _, ensure := range []func(context.Context) error{idempotencyRepository.EnsureIndexes, members.EnsureIndexes} {
		if err := ensure(ctx); err != nil {
			t.Fatalf("EnsureIndexes() error = %v", err)
		}
	}
	transactor := database.NewTransactor(db.Client())
	uc := usecase.NewApplyCollabMember(members)
	logger := discardCollabMemberLogger()
	created := rabbitmq.NewCollabJoinRequestCreatedHandler(transactor, idempotencyRepository, uc, logger)
	accepted := rabbitmq.NewCollabJoinRequestAcceptedHandler(transactor, idempotencyRepository, uc, logger)
	left := rabbitmq.NewCollabMemberLeftHandler(transactor, idempotencyRepository, uc, logger)

	collabID, userID := uuid.NewString(), uuid.NewString()
	createdJSON := fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:15:00.512345Z","collabId":"%s","userId":"%s","status":"PENDING","role":"MEMBER","createdAt":"2026-03-01T10:15:00.5Z"}`,
		uuid.NewString(), uuid.NewString(), collabID, userID)
	acceptedJSON := fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T10:20:00.1Z","collabId":"%s","userId":"%s","acceptedBy":"%s","collabMemberStatus":"ACCEPTED","role":"MEMBER","memberCreatedAt":"2026-03-01T10:15:00.5Z"}`,
		uuid.NewString(), uuid.NewString(), collabID, userID, uuid.NewString())
	leftJSON := fmt.Sprintf(`{"id":"%s","correlationId":"%s","occurredAt":"2026-03-01T11:00:00Z","collabId":"%s","userId":"%s","collabMemberStatus":"LEFT","role":"MEMBER","createdAt":"2026-03-01T10:15:00.5Z"}`,
		uuid.NewString(), uuid.NewString(), collabID, userID)

	deliveries := []struct {
		handle  func(context.Context, *message.Message) error
		payload string
	}{
		{left.Handle, leftJSON}, {accepted.Handle, acceptedJSON}, {created.Handle, createdJSON},
		{created.Handle, createdJSON}, {accepted.Handle, acceptedJSON}, {left.Handle, leftJSON},
	}
	var group sync.WaitGroup
	failures := make(chan error, len(deliveries))
	for _, delivery := range deliveries {
		group.Add(1)
		go func() {
			defer group.Done()
			var err error
			for range 10 {
				if err = delivery.handle(ctx, message.NewMessage(uuid.NewString(), []byte(delivery.payload))); err == nil {
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

	filter := bson.D{{Key: "collab_id", Value: collabID}, {Key: "user_id", Value: userID}}
	if count, err := db.Collection(collabmongo.MembersCollectionName).CountDocuments(ctx, filter); err != nil || count != 1 {
		t.Fatalf("collab_members rows = %d (error %v), want 1", count, err)
	}
	var row struct {
		Status      string    `bson:"status"`
		RequestedAt time.Time `bson:"requested_at"`
		UpdatedAt   time.Time `bson:"updated_at"`
	}
	if err := db.Collection(collabmongo.MembersCollectionName).FindOne(ctx, filter).Decode(&row); err != nil {
		t.Fatalf("FindOne() error = %v", err)
	}
	if row.Status != "LEFT" || !row.UpdatedAt.Equal(time.Date(2026, 3, 1, 11, 0, 0, 0, time.UTC)) {
		t.Fatalf("row = %+v, want the newest event (LEFT at 11:00) whatever the delivery order", row)
	}
	if !row.RequestedAt.Equal(time.Date(2026, 3, 1, 10, 15, 0, 500000000, time.UTC)) {
		t.Fatalf("requested_at = %v, want the request's date", row.RequestedAt)
	}
	if processed, err := db.Collection("processed_events").CountDocuments(ctx, bson.D{}); err != nil || processed != 3 {
		t.Fatalf("processed events = %d (error %v), want the 3 distinct events once each", processed, err)
	}
}

func startTransactionalCollabMongo(t *testing.T, ctx context.Context) *mongodriver.Database {
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

	return client.Database("post_query_collab_member_transaction_test")
}
