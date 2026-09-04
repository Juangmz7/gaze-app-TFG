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
