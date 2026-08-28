package com.app.postcommandservice.share.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.share.domain.model.PostShare;
import com.app.postcommandservice.share.infrastructure.events.PostSharedEvent;

@Component
public class PostShareEventMapper {

    public PostSharedEvent toPostSharedEvent(
            UUID eventId,
            UUID correlationId,
            PostShare postShare,
            Instant occurredAt) {
        return PostSharedEvent.builder()
                .id(eventId)
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .postId(postShare.getPostId().value())
                .userId(postShare.getUserId().value())
                .createdAt(postShare.getCreatedAt())
                .build();
    }
}
