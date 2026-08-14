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
