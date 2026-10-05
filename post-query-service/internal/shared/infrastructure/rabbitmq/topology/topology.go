// Package topology declares the RabbitMQ exchanges, queues, routing keys,
// and dead-letter wiring that post-query-service consumes and publishes to.
// Values mirror the topology already declared by post-command-service (see
// post-command-service/src/main/resources/application.yaml) so both services
// share the same broker layout.
package topology

// Exchange names.
const (
	// ExchangeUserEvents carries user domain events published by social-service.
	ExchangeUserEvents = "x.user.events"
	// ExchangePostEvents carries post domain events published by post-command-service.
	ExchangePostEvents = "x.post.events"
	// ExchangeFeedEvents carries feed-related events published by post-query-service.
	ExchangeFeedEvents = "x.feed.events"
)

// Queue names.
const (
	// QueueUserFast receives user events that should be projected quickly.
	QueueUserFast = "q.post-query-service.user.fast"
	// QueueUserSlow receives user events whose projection can tolerate more latency.
	QueueUserSlow = "q.post-query-service.user.slow"
	// QueuePost receives all post/comment/like/share/collab domain events.
	QueuePost = "q.post-query-service.post"
	// QueueFeed receives post events used to build the user's feed (recommended and trending signals).
	QueueFeed = "q.post-query-service.feed"
)

// RoutingKeyHeader is the Watermill message metadata key used to carry the
// original AMQP routing key of a delivery. post-query-service injects it in
// the subscriber marshaler (see router.NewSubscriberConfig) because a single
// queue is bound to many routing keys and handlers must dispatch on it.
const RoutingKeyHeader = "x-routing-key"

// RKFeedExhausted is the routing key used to publish UserFeedExhaustedEvent
// to ExchangeFeedEvents.
const RKFeedExhausted = "rk.post.feed.exhausted"

// UserFastRoutingKeys lists the routing keys bound to QueueUserFast.
var UserFastRoutingKeys = []string{
	"rk.user.registered",
	"rk.user.updated",
	"rk.user.block.created",
	"rk.user.follow.created",
}

// UserSlowRoutingKeys lists the routing keys bound to QueueUserSlow.
var UserSlowRoutingKeys = []string{
	"rk.user.deleted",
	"rk.user.block.deleted",
	"rk.user.follow.deleted",
}

// PostRoutingKeys lists every routing key bound to QueuePost, covering post,
// comment, like, share, and collaboration domain events.
var PostRoutingKeys = []string{
	"rk.post.created",
	"rk.post.updated",
	"rk.post.deleted",
	"rk.post.banned",
	"rk.post.viewed",
	"rk.post.like.created",
	"rk.post.like.deleted",
	"rk.post.comment.created",
	"rk.post.comment.updated",
	"rk.post.comment.deleted",
	"rk.post.comment.banned",
	"rk.post.comment.like.created",
	"rk.post.comment.like.deleted",
	"rk.post.share.created",
	"rk.post.share.deleted",
	"rk.post.collab.opened.post-created",
	"rk.post.collab.opened.existing-post",
	"rk.post.collab.closed",
	"rk.post.collab.deleted",
	"rk.post.collab.linked",
	"rk.post.collab.request.created",
	"rk.post.collab.request.accepted",
	"rk.post.collab.request.declined",
	"rk.post.collab.request.deleted",
	"rk.post.collab.member.left",
	"rk.post.collab.member.banned",
}

// FeedRoutingKeys lists the routing keys bound to QueueFeed.
var FeedRoutingKeys = []string{
	"rk.post.recommended.sent",
	"rk.post.trending.sent",
}

// Spec describes one queue's AMQP topology: the exchange it binds to, its
// own name, and the routing keys it should receive. A dead-letter exchange
// and dead-letter queue are derived from it (see DLXName, DLQName,
// FallbackRoutingKey) so every queue gets its own DLQ, matching the
// "{exchange}.dlx" / "{queue}.dlq" / "{queue}.fall-back" naming pattern used
// by post-command-service's RabbitMQConfig.
type Spec struct {
	// Exchange is the main (non-dead-letter) exchange name.
	Exchange string
	// ExchangeType is the AMQP exchange type, e.g. "topic".
	ExchangeType string
	// Queue is the queue name.
	Queue string
	// RoutingKeys lists every routing key bound from Exchange to Queue.
	RoutingKeys []string
}

// DLXName returns the dead-letter exchange name for exchange.
func DLXName(exchange string) string {
	return exchange + ".dlx"
}

// DLQName returns the dead-letter queue name for queue.
func DLQName(queue string) string {
	return queue + ".dlq"
}

// FallbackRoutingKey returns the routing key used to route dead-lettered
// messages from queue's DLX to its DLQ.
func FallbackRoutingKey(queue string) string {
	return queue + ".fall-back"
}

// UserFastSpec is the topology for QueueUserFast.
func UserFastSpec() Spec {
	return Spec{
		Exchange:     ExchangeUserEvents,
		ExchangeType: "topic",
		Queue:        QueueUserFast,
		RoutingKeys:  UserFastRoutingKeys,
	}
}

// UserSlowSpec is the topology for QueueUserSlow.
func UserSlowSpec() Spec {
	return Spec{
		Exchange:     ExchangeUserEvents,
		ExchangeType: "topic",
		Queue:        QueueUserSlow,
		RoutingKeys:  UserSlowRoutingKeys,
	}
}

// PostSpec is the topology for QueuePost.
func PostSpec() Spec {
	return Spec{
		Exchange:     ExchangePostEvents,
		ExchangeType: "topic",
		Queue:        QueuePost,
		RoutingKeys:  PostRoutingKeys,
	}
}

// FeedSpec is the topology for QueueFeed.
func FeedSpec() Spec {
	return Spec{
		Exchange:     ExchangePostEvents,
		ExchangeType: "topic",
		Queue:        QueueFeed,
		RoutingKeys:  FeedRoutingKeys,
	}
}
