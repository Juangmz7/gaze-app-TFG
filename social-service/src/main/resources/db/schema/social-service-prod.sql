-- social-service PostgreSQL schema (prod runs with ddl-auto=validate).
-- PostgreSQL is the source of truth; Neo4j is a projection fed by events.

CREATE TABLE IF NOT EXISTS users (
    id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    picture_url VARCHAR(255),
    social_media JSONB,
    account_status VARCHAR(255) NOT NULL,
    post_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_users PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS follows (
    follower_id UUID NOT NULL,
    followed_id UUID NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_follows PRIMARY KEY (followed_id, follower_id)
);

CREATE TABLE IF NOT EXISTS blocks (
    blocker_id UUID NOT NULL,
    blocked_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_blocks PRIMARY KEY (blocked_id, blocker_id)
);

CREATE TABLE IF NOT EXISTS processed_events (
    id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    target_database VARCHAR(32) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (id, target_database),
    CONSTRAINT uk_processed_events_correlation_id_target_database UNIQUE (correlation_id, target_database)
);

CREATE INDEX IF NOT EXISTS idx_processed_events_correlation_id_target_database
    ON processed_events (correlation_id, target_database);

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
