package com.app.postcommandservice.like.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeRepository;
import com.app.postcommandservice.like.domain.events.PostLikeDeletedDomainEvent;
import com.app.postcommandservice.like.infrastructure.events.PostLikeDeletedEvent;
import com.app.postcommandservice.like.infrastructure.mapper.PostLikeEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidatePostUnlikeUseCase {

    private final PostLikeRepository postLikeRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostLikeEventMapper postLikeEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void validateAndDeleteLike(ValidatePostUnlikeCommand command) {
        if (!postLikeRepository.existsByPostIdAndUserId(command.postId(), command.userId())) {
            log.info("Discarding unlike command {} because like does not exist for post {} and user {}",
                    command.id(), command.postId(), command.userId());
            return;
        }

        postLikeRepository.deleteByPostIdAndUserId(command.postId(), command.userId());

        var outboxId = UUID.randomUUID();
        var event = postLikeEventMapper.toPostLikeDeletedEvent(
                outboxId,
                command.correlationId(),
                command.postId(),
                command.userId(),
                command.source(),
                command.feedPosition(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostLikeDeletedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostLikeDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostLikeDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
