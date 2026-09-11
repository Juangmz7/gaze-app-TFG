package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabLinkedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotOpenException;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabLinkedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.commands.LinkExistingPostToCollabCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class LinkExistingPostToCollabUseCase {

    private final PostRepository postRepository;
    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public PostResponse link(LinkExistingPostToCollabCommand command) {
        var existingPost = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));
        assertOwnership(existingPost.getId().value(), existingPost.getUserId().value(), command.currentUserId());

        var collab = collabRepository.findById(command.collabId())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));
        assertCollabOpen(collab.getId(), collab.getCollabStatus());

        var membership = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId())
                .orElseThrow(() -> new CollabAdminAccessDeniedException(command.collabId(), command.currentUserId()));
        if (!membership.isAcceptedAdmin()) {
            throw new CollabAdminAccessDeniedException(command.collabId(), command.currentUserId());
        }

        var savedPost = postRepository.saveAndFlush(existingPost.linkToCollab(command.collabId()));

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabLinkedEvent(outboxId, correlationId, savedPost, occurredAt);
        saveOutboxEvent(outboxId, event);
        applicationEventPublisher.publishEvent(new CollabLinkedDomainEvent(outboxId));

        return toResponse(savedPost);
    }

    private void assertOwnership(UUID postId, UUID ownerId, UUID currentUserId) {
        if (!ownerId.equals(currentUserId)) {
            throw new PostOwnershipException(postId, currentUserId);
        }
    }

    private void assertCollabOpen(UUID collabId, ColabStatus collabStatus) {
        if (collabStatus != ColabStatus.OPEN) {
            throw new CollabNotOpenException(collabId, collabStatus);
        }
    }

    private void saveOutboxEvent(UUID outboxId, CollabLinkedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(event.correlationId())
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabLinkedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private PostResponse toResponse(com.app.postcommandservice.post.domain.model.Post post) {
        return new PostResponse(
                post.getId().value(),
                post.getUserId().value(),
                post.getCollabId(),
                post.getPostType(),
                post.getDescription().value(),
                post.getTaggedUsers().value(),
                post.getTags().value(),
                post.getInfo().title(),
                post.getMedia(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
