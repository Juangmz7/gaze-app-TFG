package com.app.postcommandservice.post.infrastructure.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Scheduled media cleanup job configuration (task 58). The upload window it compares against is
 * {@code posts.media.upload-window} ({@link PostMediaProperties}), shared with the upload and
 * confirmation flows.
 *
 * <p>{@code cron}, {@code lockAtMostFor} and {@code lockAtLeastFor} are read by the job's
 * {@code @Scheduled}/{@code @SchedulerLock} annotations through placeholders; they are bound here
 * too so a malformed value fails at startup.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "posts.media-cleanup")
public class PostMediaCleanupProperties {

    /** Spring cron of the job; hourly at minute 15 by default. {@code "-"} disables it. */
    @NotBlank
    private String cron = "0 15 * * * *";

    /** Longest a run may hold the lock if its instance dies; keep it shorter than the interval. */
    @NotNull
    private Duration lockAtMostFor = Duration.ofMinutes(55);

    /**
     * Shortest a run holds the lock, so an instance whose clock is a little behind does not run
     * the same interval again right after a fast run finished.
     */
    @NotNull
    private Duration lockAtLeastFor = Duration.ofMinutes(5);

    /** Posts read per keyset page. */
    @Min(1)
    @Max(1000)
    private int batchSize = 200;

    /** Pages processed per run, bounding the work of a single run. */
    @Min(1)
    private int maxBatchesPerRun = 10;
}
