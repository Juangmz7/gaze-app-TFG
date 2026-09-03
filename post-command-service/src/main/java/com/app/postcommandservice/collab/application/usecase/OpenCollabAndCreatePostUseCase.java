package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.OpenCollabAndCreatePostCommand;
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
import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class OpenCollabAndCreatePostUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final CollabRequestIdempotencyRepository collabRequestIdempotencyRepository;
    private final PostRepository postRepository;
    private final CreatePostUseCase createPostUseCase;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public OpenCollabAndCreatePostResponse open(OpenCollabAndCreatePostCommand command) {
        collabRequestIdempotencyRepository.acquireCorrelationLock(command.correlationId());

        var existingEntityId = collabRequestIdempotencyRepository.findEntityIdByCorrelationId(command.correlationId());
        if (existingEntityId.isPresent()) {
            var existingPost = postRepository.findById(existingEntityId.get())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record exists but post was not found: " + existingEntityId.get()));
            var existingCollabId = existingPost.getCollabId();
            if (existingCollabId == null) {
                throw new IllegalStateException("Idempotency record exists but post has no collab: " + existingPost.getId());
            }
            var existingCollab = collabRepository.findById(existingCollabId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record exists but collab was not found: " + existingCollabId));
            return toResponse(existingCollab, toResponse(existingPost));
        }

        var collab = Collab.open(
                UUID.randomUUID(),
                new CollabTitle(command.title()),
                new UserId(command.currentUserId())
        );
        var savedCollab = collabRepository.save(collab);

        var creatorMember = CollabMember.createCreatorAdmin(savedCollab.getId(), new UserId(command.currentUserId()));
        var savedCreatorMember = collabMemberRepository.save(creatorMember);

        var savedPostResponse = createPostUseCase.createPost(new CreatePostCommand(
                command.correlationId(),
                command.currentUserId(),
                savedCollab.getId(),
                PostType.COLAB,
                command.description(),
                normalizeSet(command.taggedUsers()),
                normalizeSet(command.postTags())
        ), false, false);
        var savedPost = postRepository.findById(savedPostResponse.postId())
                .orElseThrow(() -> new IllegalStateException(
                        "Saved post was not found after collab creation: " + savedPostResponse.postId()));

        collabRequestIdempotencyRepository.save(command.correlationId(), savedPost.getId().value());

        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabOpenedEvent(
                outboxId,
                command.correlationId(),
                savedCollab,
                savedCreatorMember,
                savedPost,
                occurredAt
        );
        saveOutboxEvent(command.correlationId(), outboxId, event);
        applicationEventPublisher.publishEvent(new CollabOpenedDomainEvent(outboxId));

        return toResponse(savedCollab, savedPostResponse);
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, CollabOpenedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabOpenedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private OpenCollabAndCreatePostResponse toResponse(Collab collab, PostResponse postResponse) {
        return new OpenCollabAndCreatePostResponse(
                collab.getId(),
                collab.getTitle().value(),
                collab.getCreatedBy().value(),
                collab.getCollabStatus(),
                collab.getCreatedAt(),
                postResponse
        );
    }

    private PostResponse toResponse(Post post) {
        return new PostResponse(
                post.getId().value(),
                post.getUserId().value(),
                post.getCollabId(),
                post.getPostType(),
                post.getDescription().value(),
                post.getTaggedUsers().value(),
                post.getTags().value(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }

    private Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
