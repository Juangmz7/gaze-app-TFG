package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

/** Closed projection over {@code post_media}: the blob urls the media cleanup job deletes (task 58). */
public interface PostMediaBlobView {

    UUID getPostId();

    String getUrl();

    String getThumbnailUrl();
}
