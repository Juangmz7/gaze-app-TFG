package com.app.postcommandservice.share.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.application.repository.PostShareValidationRepository;
import com.app.postcommandservice.share.domain.events.PostSharedDomainEvent;
import com.app.postcommandservice.share.domain.model.PostShare;
import com.app.postcommandservice.share.infrastructure.events.PostSharedEvent;
import com.app.postcommandservice.share.infrastructure.mapper.PostShareEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatePostShareUseCase {

    private final PostShareRepository postShareRepository;
    private final PostShareValidationRepository postShareValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostShareEventMapper postShareEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void share(CreatePostShareCommand command) {
        var post = postShareValidationRepository.findPost(command.postId());
        if (post.isEmpty()) {
            log.info("Discarding post share command {} because post {} does not exist",
                    command.id(), command.postId());
            return;
        }

        if (!isActive(post.get())) {
            log.info("Discarding post share command {} because post {} is not ACTIVE",
                    command.id(), command.postId());
            return;
        }

        if (isSelfShare(post.get(), command.userId())) {
            log.info("Discarding post share command {} because user {} cannot share own post {}",
                    command.id(), command.userId(), command.postId());
            return;
        }

        if (postShareValidationRepository.existsBlockRelationship(command.userId(), post.get().ownerUserId())) {
            log.info("Discarding post share command {} because users {} and {} are blocked",
                    command.id(), command.userId(), post.get().ownerUserId());
            return;
        }

        if (postShareRepository.existsByPostIdAndUserId(command.postId(), command.userId())) {
            log.info("Discarding duplicate post share for post {} and user {}",
                    command.postId(), command.userId());
            return;
        }

        PostShare savedShare;
        try {
            savedShare = postShareRepository.save(PostShare.create(
                    new PostId(command.postId()),
                    new UserId(command.userId())
            ));
        } catch (DataIntegrityViolationException exception) {
            log.info("Detected concurrent duplicate post share for post {} and user {}",
                    command.postId(), command.userId());
            return;
        }

        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = postShareEventMapper.toPostSharedEvent(outboxId, command.correlationId(), savedShare, occurredAt);
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostSharedDomainEvent(outboxId));
    }

    private boolean isActive(PostShareValidationRepository.ShareablePost post) {
        return post.status() == PostStatus.ACTIVE;
    }

    private boolean isSelfShare(PostShareValidationRepository.ShareablePost post, UUID currentUserId) {
        return post.ownerUserId().equals(currentUserId);
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostSharedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostSharedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
