package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Closed Spring Data JPA projection (task 35) over {@code post_media}, selecting only
 * the fields required to build {@code PostMediaUploadedEvent}'s media payload.
 */
public interface PostMediaConfirmView {

    UUID getId();

    String getUrl();

    String getThumbnailUrl();

    MediaType getMediaType();

    Integer getMediaOrder();
}
