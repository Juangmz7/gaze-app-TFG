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
	// ExchangeFeedEvents carries feed events between post-query-service
	// (UserFeedExhaustedEvent) and recommendation-service (recommended and
	// trending feeds).
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

// Routing keys bound to QueueUserFast.
const (
	RKUserRegistered    = "rk.user.registered"
	RKUserUpdated       = "rk.user.updated"
	RKUserBlockCreated  = "rk.user.block.created"
	RKUserFollowCreated = "rk.user.follow.created"
)

// Routing keys bound to QueueUserSlow.
const (
	RKUserDeleted       = "rk.user.deleted"
	RKUserBlockDeleted  = "rk.user.block.deleted"
	RKUserFollowDeleted = "rk.user.follow.deleted"
)

// Routing keys bound to QueuePost, covering post, comment, like, share, and
// collaboration domain events.
const (
	RKPostCreated                  = "rk.post.created"
	RKPostUpdated                  = "rk.post.updated"
	RKPostDeleted                  = "rk.post.deleted"
	RKPostBanned                   = "rk.post.banned"
	RKPostViewed                   = "rk.post.viewed"
	RKPostLikeCreated              = "rk.post.like.created"
	RKPostLikeDeleted              = "rk.post.like.deleted"
	RKPostCommentCreated           = "rk.post.comment.created"
	RKPostCommentUpdated           = "rk.post.comment.updated"
	RKPostCommentDeleted           = "rk.post.comment.deleted"
	RKPostCommentBanned            = "rk.post.comment.banned"
	RKPostCommentLikeCreated       = "rk.post.comment.like.created"
	RKPostCommentLikeDeleted       = "rk.post.comment.like.deleted"
	RKPostShareCreated             = "rk.post.share.created"
	RKPostShareDeleted             = "rk.post.share.deleted"
	RKPostCollabOpenedPostCreated  = "rk.post.collab.opened.post-created"
	RKPostCollabOpenedExistingPost = "rk.post.collab.opened.existing-post"
	RKPostCollabClosed             = "rk.post.collab.closed"
	RKPostCollabDeleted            = "rk.post.collab.deleted"
	RKPostCollabLinked             = "rk.post.collab.linked"
	RKPostCollabRequestCreated     = "rk.post.collab.request.created"
	RKPostCollabRequestAccepted    = "rk.post.collab.request.accepted"
	RKPostCollabRequestDeclined    = "rk.post.collab.request.declined"
	RKPostCollabRequestDeleted     = "rk.post.collab.request.deleted"
	RKPostCollabMemberLeft         = "rk.post.collab.member.left"
	RKPostCollabMemberBanned       = "rk.post.collab.member.banned"
)

// Routing keys bound to QueueFeed.
const (
	RKPostRecommendedSent = "rk.post.feed.recommended.sent"
	RKPostTrendingSent    = "rk.post.trending.sent"
)

// UserFastRoutingKeys lists the routing keys bound to QueueUserFast.
var UserFastRoutingKeys = []string{
	RKUserRegistered,
	RKUserUpdated,
	RKUserBlockCreated,
	RKUserFollowCreated,
}

// UserSlowRoutingKeys lists the routing keys bound to QueueUserSlow.
var UserSlowRoutingKeys = []string{
	RKUserDeleted,
	RKUserBlockDeleted,
	RKUserFollowDeleted,
}

// PostRoutingKeys lists every routing key bound to QueuePost, covering post,
// comment, like, share, and collaboration domain events.
var PostRoutingKeys = []string{
	RKPostCreated,
	RKPostUpdated,
	RKPostDeleted,
	RKPostBanned,
	RKPostViewed,
	RKPostLikeCreated,
	RKPostLikeDeleted,
	RKPostCommentCreated,
	RKPostCommentUpdated,
	RKPostCommentDeleted,
	RKPostCommentBanned,
	RKPostCommentLikeCreated,
	RKPostCommentLikeDeleted,
	RKPostShareCreated,
	RKPostShareDeleted,
	RKPostCollabOpenedPostCreated,
	RKPostCollabOpenedExistingPost,
	RKPostCollabClosed,
	RKPostCollabDeleted,
	RKPostCollabLinked,
	RKPostCollabRequestCreated,
	RKPostCollabRequestAccepted,
	RKPostCollabRequestDeclined,
	RKPostCollabRequestDeleted,
	RKPostCollabMemberLeft,
	RKPostCollabMemberBanned,
}

// FeedRoutingKeys lists the routing keys bound to QueueFeed.
var FeedRoutingKeys = []string{
	RKPostRecommendedSent,
	RKPostTrendingSent,
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
		Exchange:     ExchangeFeedEvents,
		ExchangeType: "topic",
		Queue:        QueueFeed,
		RoutingKeys:  FeedRoutingKeys,
	}
}
