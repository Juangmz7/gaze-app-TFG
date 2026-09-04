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

import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.application.repository.PostShareValidationRepository;
import com.app.postcommandservice.share.domain.events.PostSharedDomainEvent;
import com.app.postcommandservice.share.domain.exception.PostShareBlockedException;
import com.app.postcommandservice.share.domain.exception.PostShareTargetNotActiveException;
import com.app.postcommandservice.share.domain.exception.SelfPostShareNotAllowedException;
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
        var post = postShareValidationRepository.findPost(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertActive(post);
        assertNotSelfShare(post, command.currentUserId());
        assertNotBlocked(post, command.currentUserId());

        if (postShareRepository.existsByPostIdAndUserId(command.postId(), command.currentUserId())) {
            log.info("Discarding duplicate post share for post {} and user {}",
                    command.postId(), command.currentUserId());
            return;
        }

        PostShare savedShare;
        try {
            savedShare = postShareRepository.save(PostShare.create(
                    new PostId(command.postId()),
                    new UserId(command.currentUserId())
            ));
        } catch (DataIntegrityViolationException exception) {
            log.info("Detected concurrent duplicate post share for post {} and user {}",
                    command.postId(), command.currentUserId());
            return;
        }

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = postShareEventMapper.toPostSharedEvent(outboxId, correlationId, savedShare, occurredAt);
        saveOutboxEvent(outboxId, correlationId, event);

        applicationEventPublisher.publishEvent(new PostSharedDomainEvent(outboxId));
    }

    private void assertActive(PostShareValidationRepository.ShareablePost post) {
        if (post.status() != PostStatus.ACTIVE) {
            throw new PostShareTargetNotActiveException(post.postId(), post.status());
        }
    }

    private void assertNotSelfShare(PostShareValidationRepository.ShareablePost post, UUID currentUserId) {
        if (post.ownerUserId().equals(currentUserId)) {
            throw new SelfPostShareNotAllowedException(post.postId(), currentUserId);
        }
    }

    private void assertNotBlocked(PostShareValidationRepository.ShareablePost post, UUID currentUserId) {
        if (postShareValidationRepository.existsBlockRelationship(currentUserId, post.ownerUserId())) {
            throw new PostShareBlockedException(post.postId(), currentUserId, post.ownerUserId());
        }
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
