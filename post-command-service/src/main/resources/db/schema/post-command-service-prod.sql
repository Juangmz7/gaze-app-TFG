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

ALTER TABLE post_request_idempotency
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE post_request_idempotency
    ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

ALTER TABLE post_request_idempotency
    DROP CONSTRAINT IF EXISTS post_request_idempotency_pkey;

ALTER TABLE post_request_idempotency
    ADD CONSTRAINT pk_post_request_idempotency PRIMARY KEY (user_id, correlation_id);

-- Task 38: refresh-upload-urls endpoint persists each issued SAS hashed (never plain) with its
-- expiry, keyed naturally by post_media's own id, so a still-valid client-held SAS can be
-- recognised without re-signing.
ALTER TABLE post_media
    ADD COLUMN IF NOT EXISTS upload_sas_hash VARCHAR(255);

ALTER TABLE post_media
    ADD COLUMN IF NOT EXISTS upload_sas_expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE post_media
    ADD COLUMN IF NOT EXISTS thumbnail_sas_hash VARCHAR(255);

ALTER TABLE post_media
    ADD COLUMN IF NOT EXISTS thumbnail_sas_expires_at TIMESTAMP WITH TIME ZONE;

-- Task 39: scheduled cleanup of expired unconfirmed uploads.
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS media_purged_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_posts_status_created_at_unpurged
    ON posts (status, created_at)
    WHERE media_purged_at IS NULL;

-- ShedLock's own lock table (net.javacrumbs.shedlock), read/written via plain JDBC by
-- JdbcTemplateLockProvider so only one instance runs the hourly cleanup job at a time.
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
