package com.app.postcommandservice.view.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

public class PostView {

    private final UUID viewId;
    private final PostId postId;
    private final UserId userId;
    private final PostViewSource source;
    private final int feedPosition;
    private final int durationMs;
    private final int timeWatchedMs;
    private final int completionPercent;
    private final PostViewExitReason exitReason;
    private final Instant serverTimestamp;
    private final int replayCount;

    public PostView(
            UUID viewId,
            PostId postId,
            UserId userId,
            PostViewSource source,
            int feedPosition,
            int durationMs,
            int timeWatchedMs,
            int completionPercent,
            PostViewExitReason exitReason,
            Instant serverTimestamp,
            int replayCount) {
        this.viewId = Objects.requireNonNull(viewId, "viewId must not be null");
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.exitReason = Objects.requireNonNull(exitReason, "exitReason must not be null");
        this.serverTimestamp = Objects.requireNonNull(serverTimestamp, "serverTimestamp must not be null");
        if (feedPosition < 0) {
            throw new IllegalArgumentException("feedPosition must be zero or greater");
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be greater than zero");
        }
        if (timeWatchedMs < 0 || timeWatchedMs > durationMs) {
            throw new IllegalArgumentException("timeWatchedMs must be between 0 and durationMs");
        }
        if (completionPercent < 0 || completionPercent > 100) {
            throw new IllegalArgumentException("completionPercent must be between 0 and 100");
        }
        if (replayCount <= 0) {
            throw new IllegalArgumentException("replayCount must be greater than zero");
        }
        this.feedPosition = feedPosition;
        this.durationMs = durationMs;
        this.timeWatchedMs = timeWatchedMs;
        this.completionPercent = completionPercent;
        this.replayCount = replayCount;
    }

    public UUID getViewId() {
        return viewId;
    }

    public PostId getPostId() {
        return postId;
    }

    public UserId getUserId() {
        return userId;
    }

    public PostViewSource getSource() {
        return source;
    }

    public int getFeedPosition() {
        return feedPosition;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public int getTimeWatchedMs() {
        return timeWatchedMs;
    }

    public int getCompletionPercent() {
        return completionPercent;
    }

    public PostViewExitReason getExitReason() {
        return exitReason;
    }

    public Instant getServerTimestamp() {
        return serverTimestamp;
    }

    public int getReplayCount() {
        return replayCount;
    }
}
