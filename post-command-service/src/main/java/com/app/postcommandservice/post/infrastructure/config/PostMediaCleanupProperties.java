package com.app.postcommandservice.post.infrastructure.config;

import jakarta.validation.constraints.Min;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the scheduled media-cleanup job (task 39). The job's cron expression and
 * ShedLock {@code lockAtMostFor} stay as plain {@code ${...}} placeholders resolved directly
 * on the {@code @Scheduled}/{@code @SchedulerLock} annotations (Spring does not support
 * resolving those from a typed {@code @ConfigurationProperties} bean), configured under the
 * same {@code posts.media-cleanup} prefix in {@code application.yaml}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "posts.media-cleanup")
public class PostMediaCleanupProperties {

    /**
     * Maximum number of candidate posts fetched per page within a single run, keeping each
     * page's query cost bounded regardless of how many posts are currently expired.
     */
    @Min(1)
    private int batchSize = 200;

    /**
     * Maximum number of pages processed in a single run, bounding the total work (and the
     * time the ShedLock is held) even if far more posts are expired than can be cleaned up in
     * one run; the remainder is picked up by the next hourly run.
     */
    @Min(1)
    private int maxBatchesPerRun = 10;
}
