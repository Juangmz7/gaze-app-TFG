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
