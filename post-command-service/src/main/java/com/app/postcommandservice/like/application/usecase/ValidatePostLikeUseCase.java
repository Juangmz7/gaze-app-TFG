package com.app.postcommandservice.like.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeRepository;
import com.app.postcommandservice.like.application.repository.PostLikeValidationRepository;
import com.app.postcommandservice.like.domain.events.PostLikeCreatedDomainEvent;
import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.infrastructure.events.PostLikeCreatedEvent;
import com.app.postcommandservice.like.infrastructure.mapper.PostLikeEventMapper;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidatePostLikeUseCase {

    private final PostLikeRepository postLikeRepository;
    private final PostLikeValidationRepository postLikeValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostLikeEventMapper postLikeEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void validateAndCreateLike(ValidatePostLikeCommand command) {
        var activePost = postLikeValidationRepository.findActivePost(command.postId());
        if (activePost.isEmpty()) {
            log.info("Discarding like command {} because post {} does not exist or is not ACTIVE",
                    command.id(), command.postId());
            return;
        }

        var postOwnerId = activePost.get().ownerUserId();
        if (postLikeValidationRepository.existsBlockRelationship(command.userId(), postOwnerId)) {
            log.info("Discarding like command {} because users {} and {} are blocked",
                    command.id(), command.userId(), postOwnerId);
            return;
        }

        if (postLikeRepository.existsByPostIdAndUserId(command.postId(), command.userId())) {
            log.info("Discarding like command {} because like already exists for post {} and user {}",
                    command.id(), command.postId(), command.userId());
            return;
        }

        var savedLike = postLikeRepository.save(PostLike.create(
                new PostId(command.postId()),
                new UserId(command.userId())
        ));

        var outboxId = UUID.randomUUID();
        var event = postLikeEventMapper.toPostLikeCreatedEvent(
                outboxId,
                command.correlationId(),
                savedLike,
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostLikeCreatedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostLikeCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostLikeCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
