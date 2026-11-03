package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Closed Spring Data JPA projection (task 39) over {@code post_media} for the scheduled
 * media-cleanup job. Carries its owning {@code postId} so a whole page of candidate posts can
 * be resolved with a single {@code IN} query and grouped in memory, avoiding one media query
 * per post (N+1).
 */
public interface PostMediaCleanupMediaView {

    UUID getPostId();

    UUID getId();

    MediaType getMediaType();

    String getUrl();

    String getThumbnailUrl();
}
