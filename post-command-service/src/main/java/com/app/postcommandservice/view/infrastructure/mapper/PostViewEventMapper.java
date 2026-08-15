package com.app.postcommandservice.view.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;

@Component
public class PostViewEventMapper {

    public PostViewedEvent toPostViewedEvent(
            UUID eventId,
            UUID correlationId,
            PostView postView,
            Instant occurredAt) {
        return PostViewedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .viewId(postView.getViewId())
                .postId(postView.getPostId().value())
                .userId(postView.getUserId().value())
                .source(postView.getSource())
                .feedPosition(postView.getFeedPosition())
                .durationMs(postView.getDurationMs())
                .timeWatchedMs(postView.getTimeWatchedMs())
                .completionPercent(postView.getCompletionPercent())
                .exitReason(postView.getExitReason())
                .serverTimestamp(postView.getServerTimestamp())
                .replayCount(postView.getReplayCount())
                .build();
    }
}
