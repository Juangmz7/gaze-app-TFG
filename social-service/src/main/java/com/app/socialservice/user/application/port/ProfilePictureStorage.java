package com.app.socialservice.user.application.port;

import java.util.Optional;

import com.app.socialservice.user.application.dto.ProfilePictureUploadUrlResponse;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;

/**
 * Port to the media container that holds avatars.
 */
public interface ProfilePictureStorage {

    /**
     * A write-only SAS URL for the blob, the same blob's bare URL (what the client stores in
     * its profile) and when the SAS expires.
     */
    ProfilePictureUploadUrlResponse createUploadUrl(ProfilePictureBlobName blobName);

    /**
     * The blob name when {@code url} points into the media container (whatever its scheme,
     * with or without a query string), empty otherwise.
     */
    Optional<String> findContainerBlobName(String url);

    /**
     * The bare URL of the blob, never signed.
     */
    String blobUrl(ProfilePictureBlobName blobName);

    /**
     * Whether the blob has been uploaded. A network call.
     */
    boolean exists(ProfilePictureBlobName blobName);
}
