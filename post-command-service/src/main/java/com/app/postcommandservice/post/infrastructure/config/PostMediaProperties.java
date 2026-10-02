package com.app.postcommandservice.post.infrastructure.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

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
}
