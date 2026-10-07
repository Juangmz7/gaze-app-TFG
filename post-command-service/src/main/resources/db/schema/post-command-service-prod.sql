-- post-command-service schema (prod runs with ddl-auto=validate).
-- Plain CREATE statements: there is no production data yet, so the schema is
-- defined in its final shape instead of as a sequence of ALTER/UPDATE steps.

-- --- Collabs ---
CREATE TABLE IF NOT EXISTS collabs (
    id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL,
    collab_status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_collabs PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS collab_members (
    collab_id UUID NOT NULL,
    user_id UUID NOT NULL,
    collab_member_status VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_collab_members PRIMARY KEY (collab_id, user_id)
);

CREATE TABLE IF NOT EXISTS collab_request_idempotency (
    correlation_id UUID NOT NULL,
    entity_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_collab_request_idempotency PRIMARY KEY (correlation_id),
    CONSTRAINT uk_collab_request_idempotency_entity_id UNIQUE (entity_id)
);

CREATE INDEX IF NOT EXISTS idx_collab_request_idempotency_entity_id
    ON collab_request_idempotency (entity_id);

-- --- Posts ---
CREATE TABLE IF NOT EXISTS posts (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    collab_id UUID,
    description VARCHAR(4000) NOT NULL,
    post_type VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_posts PRIMARY KEY (id),
    CONSTRAINT fk_posts_collab FOREIGN KEY (collab_id) REFERENCES collabs (id)
);

CREATE TABLE IF NOT EXISTS post_tags (
    post_id UUID NOT NULL,
    tag_value VARCHAR(255) NOT NULL,
    CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS post_request_idempotency (
    correlation_id UUID NOT NULL,
    post_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_request_idempotency PRIMARY KEY (correlation_id),
    CONSTRAINT uk_post_request_idempotency_post_id UNIQUE (post_id)
);

CREATE INDEX IF NOT EXISTS idx_post_request_idempotency_post_id
    ON post_request_idempotency (post_id);

CREATE TABLE IF NOT EXISTS post_media (
    id UUID NOT NULL,
    post_id UUID NOT NULL,
    url VARCHAR(255) NOT NULL,
    thumbnail_url VARCHAR(255),
    media_type VARCHAR(255) NOT NULL,
    duration INTEGER,
    media_order INTEGER NOT NULL,
    CONSTRAINT pk_post_media PRIMARY KEY (id),
    CONSTRAINT uk_post_media_post_id_media_order UNIQUE (post_id, media_order),
    CONSTRAINT ck_post_media_order_positive CHECK (media_order > 0),
    CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_post_media_post_id
    ON post_media (post_id);

CREATE TABLE IF NOT EXISTS post_media_tagged_users (
    post_media_id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    CONSTRAINT pk_post_media_tagged_users PRIMARY KEY (post_media_id, username),
    CONSTRAINT fk_post_media_tagged_users_post_media
        FOREIGN KEY (post_media_id) REFERENCES post_media (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_post_media_tagged_users_post_media_id
    ON post_media_tagged_users (post_media_id);

-- --- Comments ---
CREATE TABLE IF NOT EXISTS comments (
    id UUID NOT NULL,
    post_id UUID NOT NULL,
    user_id UUID NOT NULL,
    reply_to UUID,
    content VARCHAR(4000) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_comments PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS comment_request_idempotency (
    correlation_id UUID NOT NULL,
    comment_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_comment_request_idempotency PRIMARY KEY (correlation_id),
    CONSTRAINT uk_comment_request_idempotency_comment_id UNIQUE (comment_id)
);

CREATE INDEX IF NOT EXISTS idx_comment_request_idempotency_comment_id
    ON comment_request_idempotency (comment_id);

-- --- Interactions ---
CREATE TABLE IF NOT EXISTS post_likes (
    post_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source VARCHAR(255) NOT NULL,
    feed_position INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_likes PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS comment_likes (
    comment_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source VARCHAR(255) NOT NULL,
    feed_position INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_comment_likes PRIMARY KEY (comment_id, user_id)
);

CREATE TABLE IF NOT EXISTS post_shares (
    post_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_shares PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS post_views (
    id UUID NOT NULL,
    post_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source VARCHAR(255) NOT NULL,
    exit_reason VARCHAR(255) NOT NULL,
    feed_position INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    time_watched_ms INTEGER NOT NULL,
    completion_percent INTEGER NOT NULL,
    replay_count INTEGER NOT NULL,
    server_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_views PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_post_views_post_user
    ON post_views (post_id, user_id);

-- --- User read models (projected from social-service events) ---
CREATE TABLE IF NOT EXISTS users (
    id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS user_follows (
    follower_id UUID NOT NULL,
    followed_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_follows PRIMARY KEY (followed_id, follower_id)
);

CREATE TABLE IF NOT EXISTS blocks (
    blocker_id UUID NOT NULL,
    blocked_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_blocks PRIMARY KEY (blocked_id, blocker_id)
);

-- --- Messaging ---
CREATE TABLE IF NOT EXISTS processed_events (
    id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_processed_events_correlation_id
    ON processed_events (correlation_id);

-- Transactional outbox. exchange/routing_key are resolved at insert time;
-- status: PENDING -> PROCESSING -> PROCESSED, or FAILED after max attempts.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    exchange VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(255) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    locked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);

-- The relay only ever scans unfinished rows
CREATE INDEX IF NOT EXISTS idx_outbox_event_unfinished
    ON outbox_event (created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
