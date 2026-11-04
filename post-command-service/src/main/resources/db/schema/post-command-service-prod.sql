-- post-command-service schema (prod runs with ddl-auto=validate).
-- Idempotent: safe to run on an empty database and on one created by any
-- earlier version of this file (PostCommandProductionSchemaValidationIT covers
-- the legacy shapes).
--   1. Final shape: CREATE ... IF NOT EXISTS for every table and index, so a
--      fresh database is complete after this part.
--   2. Tracked migrations for databases that predate a change: each step is a
--      no-op when the column/constraint/table is already in its final shape.

-- ===== 1. Final shape =====

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

-- Scoped by (user_id, correlation_id): one user cannot reuse or discover another
-- user's post by colliding on a client-chosen correlation id. request_hash detects
-- the same key reused with a different payload.
CREATE TABLE IF NOT EXISTS post_request_idempotency (
    user_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    post_id UUID NOT NULL,
    request_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_post_request_idempotency PRIMARY KEY (user_id, correlation_id),
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
    email VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    social_media JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
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

-- ===== 2. Tracked migrations =====

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS collab_id UUID;

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS post_type VARCHAR(255);

UPDATE posts
SET post_type = 'BASIC'
WHERE post_type IS NULL;

ALTER TABLE posts
    ALTER COLUMN post_type SET NOT NULL;

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

ALTER TABLE posts
    DROP CONSTRAINT IF EXISTS fk_posts_collab;

ALTER TABLE posts
    ADD CONSTRAINT fk_posts_collab
        FOREIGN KEY (collab_id) REFERENCES collabs(id);

ALTER TABLE post_likes
    ADD COLUMN IF NOT EXISTS source VARCHAR(255);

ALTER TABLE post_likes
    ADD COLUMN IF NOT EXISTS feed_position INTEGER;

UPDATE post_likes
SET source = 'HOME_FEED'
WHERE source IS NULL;

UPDATE post_likes
SET feed_position = 0
WHERE feed_position IS NULL;

ALTER TABLE post_likes
    ALTER COLUMN source SET NOT NULL;

ALTER TABLE post_likes
    ALTER COLUMN feed_position SET NOT NULL;

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
CREATE TABLE IF NOT EXISTS comment_request_idempotency (
    correlation_id UUID NOT NULL,
    comment_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_comment_request_idempotency PRIMARY KEY (correlation_id),
    CONSTRAINT uk_comment_request_idempotency_comment_id UNIQUE (comment_id)
);

CREATE INDEX IF NOT EXISTS idx_comment_request_idempotency_comment_id
    ON comment_request_idempotency (comment_id);

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);

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
    CONSTRAINT ck_post_media_order_positive CHECK (media_order > 0)
);

ALTER TABLE post_media
    DROP CONSTRAINT IF EXISTS fk_post_media_post;

ALTER TABLE post_media
    ADD CONSTRAINT fk_post_media_post
        FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_post_media_post_id
    ON post_media (post_id);

ALTER TABLE posts
    DROP COLUMN IF EXISTS title;

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Task 31: move taggedUsers storage from posts to post_media (order = 1)
CREATE TABLE IF NOT EXISTS post_media_tagged_users (
    post_media_id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    CONSTRAINT pk_post_media_tagged_users PRIMARY KEY (post_media_id, username)
);

ALTER TABLE post_media_tagged_users
    DROP CONSTRAINT IF EXISTS fk_post_media_tagged_users_post_media;

ALTER TABLE post_media_tagged_users
    ADD CONSTRAINT fk_post_media_tagged_users_post_media
        FOREIGN KEY (post_media_id) REFERENCES post_media(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_post_media_tagged_users_post_media_id
    ON post_media_tagged_users (post_media_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'post_tagged_users') THEN
        INSERT INTO post_media_tagged_users (post_media_id, username)
        SELECT pm.id, ptu.username
        FROM post_tagged_users ptu
        JOIN post_media pm ON pm.post_id = ptu.post_id AND pm.media_order = 1
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

DROP TABLE IF EXISTS post_tagged_users;

-- Security fix: scope post-creation request idempotency by (userId, correlationId) instead of
-- correlationId alone, so one user cannot reuse/discover another user's post by colliding on a
-- client-chosen correlation id. Also adds request_hash to detect same-key/different-payload reuse.
ALTER TABLE post_request_idempotency
    ADD COLUMN IF NOT EXISTS user_id UUID;

UPDATE post_request_idempotency pri
SET user_id = p.user_id
FROM posts p
WHERE p.id = pri.post_id
  AND pri.user_id IS NULL;

-- Rows that could not be backfilled (e.g. referencing a post that no longer exists) cannot be
-- scoped to an owner; drop them so SET NOT NULL succeeds. They are only replay records.
DELETE FROM post_request_idempotency
WHERE user_id IS NULL;

ALTER TABLE post_request_idempotency
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE post_request_idempotency
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

ALTER TABLE post_request_idempotency
    DROP CONSTRAINT IF EXISTS post_request_idempotency_pkey;

-- A fresh database already got pk_post_request_idempotency from part 1
ALTER TABLE post_request_idempotency
    DROP CONSTRAINT IF EXISTS pk_post_request_idempotency;

ALTER TABLE post_request_idempotency
    ADD CONSTRAINT pk_post_request_idempotency PRIMARY KEY (user_id, correlation_id);

-- --- User read model: email and bio (description, social media) ---
-- Rows projected before this change have no email to backfill, so the column
-- stays nullable here; new databases get NOT NULL from part 1.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email VARCHAR(255);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS description VARCHAR(255);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS social_media JSONB;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email
    ON users (email);

-- --- Outbox: upgrade the pre-relay outbox_event shape ---
ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS exchange VARCHAR(255);

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS routing_key VARCHAR(255);

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS last_error TEXT;

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP WITH TIME ZONE;
