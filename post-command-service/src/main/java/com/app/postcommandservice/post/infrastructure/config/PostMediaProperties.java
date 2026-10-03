package com.app.postcommandservice.post.infrastructure.config;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Post-media business-rule configuration, shared by the media URL generator / upload SAS
 * signer (task 32) and the upload confirmation and cleanup flows (tasks 35, 38, 39).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "posts.media")
public class PostMediaProperties {

    /**
     * How long after a post is created its media upload SAS URLs may remain valid
     * (and, for later tasks, how long an unconfirmed upload is allowed to exist before
     * cleanup). No upload SAS may ever be valid past {@code post.createdAt + uploadWindow}.
     */
    @NotNull
    private Duration uploadWindow = Duration.ofHours(48);

    /**
     * Maximum number of usernames that may be tagged on a single {@code PostMedia} item
     * (task 33). Enforced in the application layer when building media for a new post.
     */
    @Min(1)
    private int maxTaggedUsers = 30;

    /**
     * Maximum size, in bytes, of an IMAGE blob (or a VIDEO's thumbnail blob). Enforced by
     * {@code MediaVerifier} (task 36) before downloading the blob's content, using the size
     * reported by the blob's properties.
     */
    @Positive
    private long maxImageBytes = 10L * 1024 * 1024;

    /**
     * Maximum size, in bytes, of a VIDEO content blob. Enforced by {@code MediaVerifier}
     * (task 36) before downloading the blob's content.
     */
    @Positive
    private long maxVideoBytes = 200L * 1024 * 1024;

    /**
     * Optional maximum VIDEO duration, in seconds. {@code null} means no limit. Enforced by
     * {@code MediaVerifier} (task 36) after extracting the duration from the blob.
     */
    private Integer maxVideoDurationSeconds;

    /**
     * Image formats {@code MediaVerifier} accepts for an IMAGE blob (or a VIDEO's thumbnail),
     * identified by real content (magic bytes), never by the client-set Content-Type property.
     */
    @NotNull
    private Set<String> allowedImageFormats = Set.of("JPEG", "PNG", "WEBP");

    /**
     * Video (ISO BMFF) formats {@code MediaVerifier} accepts for a VIDEO content blob,
     * identified by real content (magic bytes / {@code ftyp} major brand), never by the
     * client-set Content-Type property.
     */
    @NotNull
    private Set<String> allowedVideoFormats = Set.of("MP4", "MOV");
}
