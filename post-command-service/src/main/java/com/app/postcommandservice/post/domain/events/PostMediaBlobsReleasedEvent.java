package com.app.postcommandservice.post.domain.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The blobs of a hard-deleted, never-accepted post are no longer referenced (task 60) and are
 * deleted once the deleting transaction commits. Deliberately not a {@code DomainEvent}: it has no
 * outbox row and is never sent to the broker; it only defers the storage calls until after commit.
 */
public record PostMediaBlobsReleasedEvent(UUID postId, List<String> blobUrls) {

    public PostMediaBlobsReleasedEvent {
        Objects.requireNonNull(postId, "postId must not be null");
        blobUrls = blobUrls == null ? List.of() : List.copyOf(blobUrls);
    }
}
