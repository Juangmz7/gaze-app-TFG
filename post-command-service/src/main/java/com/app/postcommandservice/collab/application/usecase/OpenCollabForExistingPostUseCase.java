package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.OpenCollabForExistingPostCommand;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.application.repository.CollabRequestIdempotencyRepository;
import com.app.postcommandservice.collab.domain.events.CollabOpenedDomainEvent;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabOpenedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class OpenCollabForExistingPostUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final CollabRequestIdempotencyRepository collabRequestIdempotencyRepository;
    private final PostRepository postRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public OpenCollabAndCreatePostResponse open(OpenCollabForExistingPostCommand command) {
        collabRequestIdempotencyRepository.acquireCorrelationLock(command.correlationId());

        var existingCollabId = collabRequestIdempotencyRepository.findEntityIdByCorrelationId(command.correlationId());
        if (existingCollabId.isPresent()) {
            return replayExistingCollab(existingCollabId.get());
        }

        var existingPost = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));
        assertActive(existingPost);
        assertOwnership(existingPost, command.currentUserId());

        var savedCollab = collabRepository.save(Collab.open(
                UUID.randomUUID(),
                new CollabTitle(command.title()),
                new UserId(command.currentUserId())
        ));
        var savedCreatorMember = collabMemberRepository.save(
                CollabMember.createCreatorAdmin(savedCollab.getId(), new UserId(command.currentUserId()))
        );
        var savedPost = postRepository.saveAndFlush(existingPost.linkToCollab(savedCollab.getId()));

        collabRequestIdempotencyRepository.save(command.correlationId(), savedCollab.getId());
        publishCollabOpenedEvent(command.correlationId(), savedCollab, savedCreatorMember, savedPost);

        return toResponse(savedCollab, savedPost);
    }

    private OpenCollabAndCreatePostResponse replayExistingCollab(UUID collabId) {
        var existingCollab = collabRepository.findById(collabId)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Idempotency record exists but collab was not found: %s", collabId)));
        var existingPost = postRepository.findByCollabId(collabId)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Idempotency record exists but post was not found for collab: %s", collabId)));

        return toResponse(existingCollab, existingPost);
    }

    private void assertActive(Post post) {
        if (post.getStatus() != PostStatus.ACTIVE) {
            throw new PostNotActiveException(post.getId().value(), post.getStatus(), "open a collab");
        }
    }

    private void assertOwnership(Post post, UUID currentUserId) {
        if (!post.getUserId().value().equals(currentUserId)) {
            throw new PostOwnershipException(post.getId().value(), currentUserId);
        }
    }

    private void publishCollabOpenedEvent(
            UUID correlationId,
            Collab savedCollab,
            CollabMember savedCreatorMember,
            Post savedPost) {
        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabOpenedEvent(
                outboxId,
                correlationId,
                savedCollab,
                savedCreatorMember,
                savedPost,
                occurredAt
        );
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabOpenedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
        applicationEventPublisher.publishEvent(new CollabOpenedDomainEvent(outboxId));
    }

    private OpenCollabAndCreatePostResponse toResponse(Collab collab, Post post) {
        return new OpenCollabAndCreatePostResponse(
                collab.getId(),
                collab.getTitle().value(),
                collab.getCreatedBy().value(),
                collab.getCollabStatus(),
                collab.getCreatedAt(),
                toPostResponse(post)
        );
    }

    private PostResponse toPostResponse(Post post) {
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
