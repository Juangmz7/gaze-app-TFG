package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Closed Spring Data JPA projection (task 38) over {@code post_media}, selecting the fields
 * required to decide, per media item, whether a client-held SAS url is still the legitimately
 * issued one (persisted hash + expiry) or a fresh SAS must be signed.
 */
public interface PostMediaRefreshUploadUrlsView {

    UUID getId();

    String getUrl();

    String getThumbnailUrl();

    MediaType getMediaType();

    Integer getMediaOrder();

    String getUploadSasHash();

    Instant getUploadSasExpiresAt();

    String getThumbnailSasHash();

    Instant getThumbnailSasExpiresAt();
}
