CREATE TABLE IF NOT EXISTS processed_events (
    id UUID NOT NULL,
    target_database VARCHAR(32) NOT NULL DEFAULT 'POSTGRES',
    correlation_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (id, target_database),
    CONSTRAINT uk_processed_events_correlation_id_target_database UNIQUE (correlation_id, target_database)
);

ALTER TABLE processed_events
    ADD COLUMN IF NOT EXISTS target_database VARCHAR(32);

UPDATE processed_events
SET target_database = 'POSTGRES'
WHERE target_database IS NULL;

WITH ranked_processed_events AS (
    SELECT ctid,
           ROW_NUMBER() OVER (
               PARTITION BY correlation_id, target_database
               ORDER BY processed_at ASC, id ASC
           ) AS row_number
    FROM processed_events
)
DELETE
FROM processed_events
WHERE ctid IN (
    SELECT ctid
    FROM ranked_processed_events
    WHERE row_number > 1
);

DROP INDEX IF EXISTS idx_processed_events_correlation_id;

ALTER TABLE processed_events
    DROP CONSTRAINT IF EXISTS uk_processed_events_correlation_id_target_database;

ALTER TABLE processed_events
    DROP CONSTRAINT IF EXISTS processed_events_pkey;

ALTER TABLE processed_events
    ALTER COLUMN target_database SET DEFAULT 'POSTGRES';

ALTER TABLE processed_events
    ALTER COLUMN target_database SET NOT NULL;

ALTER TABLE processed_events
    ADD CONSTRAINT processed_events_pkey PRIMARY KEY (id, target_database);

ALTER TABLE processed_events
    ADD CONSTRAINT uk_processed_events_correlation_id_target_database
        UNIQUE (correlation_id, target_database);

CREATE INDEX IF NOT EXISTS idx_processed_events_correlation_id_target_database
    ON processed_events (correlation_id, target_database);
