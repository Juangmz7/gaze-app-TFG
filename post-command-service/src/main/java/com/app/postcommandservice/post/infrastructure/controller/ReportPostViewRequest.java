package com.app.postcommandservice.post.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

public record ReportPostViewRequest(
        @NotNull(message = "viewId is required")
        UUID viewId,
        @NotNull(message = "postId is required")
        UUID postId,
        @Valid
        @NotNull(message = "context is required")
        ViewContextRequest context,
        @Valid
        @NotNull(message = "playbackMetrics is required")
        PlaybackMetricsRequest playbackMetrics
) {

    public record ViewContextRequest(
            @NotBlank(message = "context.source is required")
            @Pattern(
                    regexp = "home_feed|user_profile|search",
                    message = "context.source must be one of: home_feed, user_profile, search"
            )
            String source,
            @NotNull(message = "context.feedPosition is required")
            @PositiveOrZero(message = "context.feedPosition must be zero or greater")
            Integer feedPosition
    ) {
        public PostViewSource toSource() {
            return switch (source) {
                case "home_feed" -> PostViewSource.HOME_FEED;
                case "user_profile" -> PostViewSource.USER_PROFILE;
                case "search" -> PostViewSource.SEARCH;
                default -> throw new IllegalArgumentException("Unsupported context.source: " + source);
            };
        }
    }

    public record PlaybackMetricsRequest(
            @NotNull(message = "playbackMetrics.durationMs is required")
            @Positive(message = "playbackMetrics.durationMs must be greater than zero")
            Integer durationMs,
            @NotNull(message = "playbackMetrics.timeWatchedMs is required")
            @PositiveOrZero(message = "playbackMetrics.timeWatchedMs must be zero or greater")
            Integer timeWatchedMs,
            @NotNull(message = "playbackMetrics.completionPercent is required")
            @Min(value = 0, message = "playbackMetrics.completionPercent must be between 0 and 100")
            @Max(value = 100, message = "playbackMetrics.completionPercent must be between 0 and 100")
            Integer completionPercent,
            @NotBlank(message = "playbackMetrics.exitReason is required")
            @Pattern(
                    regexp = "scroll_next|video_completed|app_backgrounded|navigated_away",
                    message = "playbackMetrics.exitReason must be one of: scroll_next, video_completed, "
                            + "app_backgrounded, navigated_away"
            )
            String exitReason
    ) {
        @AssertTrue(message = "playbackMetrics.timeWatchedMs must be less than or equal to durationMs")
        public boolean hasValidWatchDuration() {
            if (durationMs == null || timeWatchedMs == null) {
                return true;
            }
            return timeWatchedMs <= durationMs;
        }

        public PostViewExitReason toExitReason() {
            return switch (exitReason) {
                case "scroll_next" -> PostViewExitReason.SCROLL_NEXT;
                case "video_completed" -> PostViewExitReason.VIDEO_COMPLETED;
                case "app_backgrounded" -> PostViewExitReason.APP_BACKGROUNDED;
                case "navigated_away" -> PostViewExitReason.NAVIGATED_AWAY;
                default -> throw new IllegalArgumentException("Unsupported playbackMetrics.exitReason: " + exitReason);
            };
        }
    }
}
