from enum import StrEnum

# --- Exchanges ---
USER_EXCHANGE = "x.user.events"
POST_EXCHANGE = "x.post.events"
FEED_EXCHANGE = "x.feed.events"


# --- Dead Letter Exchanges ---
USER_DLX = "x.recommendation-service.user.dlx"
POST_DLX = "x.recommendation-service.post.dlx"
FEED_DLX = "x.recommendation-service.feed.dlx"


# --- Queues (incoming) ---
USER_QUEUE = "q.recommendation-service.user"
POST_QUEUE = "q.recommendation-service.post"
FEED_QUEUE = "q.recommendation-service.feed"


# --- Dead Letter Queues ---
USER_DLQ = f"{USER_QUEUE}-dlq"
POST_DLQ = f"{POST_QUEUE}-dlq"
FEED_DLQ = f"{FEED_QUEUE}-dlq"


# --- User routing keys ---
class UserRoutingKey(StrEnum):
    FOLLOW_DELETED = "rk.user.follow.deleted"
    FOLLOW_CREATED = "rk.user.follow.created"
    BLOCK_DELETED = "rk.user.block.deleted"
    DELETED = "rk.user.deleted"
    BLOCK_CREATED = "rk.user.block.created"
    REGISTERED = "rk.user.registered"
    UPDATED = "rk.user.updated"


# --- Post routing keys ---
class PostRoutingKey(StrEnum):
    SHARE_DELETED = "rk.post.share.deleted"
    SHARE_CREATED = "rk.post.share.created"
    COLLAB_OPENED_WITH_POST_CREATED = "rk.post.collab.opened.post-created"
    COLLAB_OPENED_FOR_EXISTING_POST = "rk.post.collab.opened.existing-post"
    COLLAB_LINKED = "rk.post.collab.linked"
    COLLAB_DELETED = "rk.post.collab.deleted"
    COLLAB_REQUEST_CREATED = "rk.post.collab.request.created"
    COLLAB_REQUEST_DELETED = "rk.post.collab.request.deleted"
    COMMENT_LIKE_DELETED = "rk.post.comment.like.deleted"
    COMMENT_LIKE_CREATED = "rk.post.comment.like.created"
    COMMENT_DELETED = "rk.post.comment.deleted"
    COMMENT_CREATED = "rk.post.comment.created"
    VIEWED = "rk.post.viewed"
    LIKE_DELETED = "rk.post.like.deleted"
    LIKE_CREATED = "rk.post.like.created"
    BANNED = "rk.post.banned"
    DELETED = "rk.post.deleted"
    UPDATED = "rk.post.updated"
    CREATED = "rk.post.created"


# --- Feed routing keys ---
# Published by post-query-service on x.feed.events. Their own queue: one slow
# pipeline run must not delay the post events.
class FeedRoutingKey(StrEnum):
    FEED_EXHAUSTED = "rk.post.feed.exhausted"


FEED_ROUTING_KEYS = [rk.value for rk in FeedRoutingKey]

USER_ROUTING_KEYS = [rk.value for rk in UserRoutingKey]
POST_ROUTING_KEYS = [rk.value for rk in PostRoutingKey]


# --- Dead Letter routing keys ---
USER_DLQ_ROUTING_KEY = "rk.recommendation-service.user.dlq"
POST_DLQ_ROUTING_KEY = "rk.recommendation-service.post.dlq"
FEED_DLQ_ROUTING_KEY = "rk.recommendation-service.feed.dlq"


# --- Routing keys: outgoing ---
FEED_RECOMMENDED_SENT_RK = "rk.post.feed.recommended.sent"
FEED_TRENDING_SENT_RK = "rk.post.trending.sent"


# --- Incoming queues ---
INCOMING_QUEUES = [
    {
        "exchange": USER_EXCHANGE,
        "queue": USER_QUEUE,
        "routing_keys": USER_ROUTING_KEYS,
        "dead_letter_exchange": USER_DLX,
        "dead_letter_queue": USER_DLQ,
        "dead_letter_routing_key": USER_DLQ_ROUTING_KEY,
    },
    {
        "exchange": POST_EXCHANGE,
        "queue": POST_QUEUE,
        "routing_keys": POST_ROUTING_KEYS,
        "dead_letter_exchange": POST_DLX,
        "dead_letter_queue": POST_DLQ,
        "dead_letter_routing_key": POST_DLQ_ROUTING_KEY,
    },
    {
        "exchange": FEED_EXCHANGE,
        "queue": FEED_QUEUE,
        "routing_keys": FEED_ROUTING_KEYS,
        "dead_letter_exchange": FEED_DLX,
        "dead_letter_queue": FEED_DLQ,
        "dead_letter_routing_key": FEED_DLQ_ROUTING_KEY,
    },
]


# --- Bindings to remove ---
# Durable bindings outlive the code that declared them: a broker that already ran
# the old topology would keep sending feed requests to the post queue as well.
OBSOLETE_BINDINGS = [
    {
        "exchange": FEED_EXCHANGE,
        "queue": POST_QUEUE,
        "routing_keys": FEED_ROUTING_KEYS,
    },
]


# --- Outgoing exchanges ---
OUTGOING_EXCHANGES = [
    FEED_EXCHANGE,
]
