package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostCreatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.events.PostCreatedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class CreatePostUseCase {

    private final PostRepository postRepository;
    private final PostRequestIdempotencyRepository postRequestIdempotencyRepository;
    private final TaggedUserValidationRepository taggedUserValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostEventMapper postEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public PostResponse createPost(CreatePostCommand command) {
        postRequestIdempotencyRepository.acquireCorrelationLock(command.correlationId());

        var existingPostId = postRequestIdempotencyRepository.findPostIdByCorrelationId(command.correlationId());
        if (existingPostId.isPresent()) {
            return postRepository.findById(existingPostId.get())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record exists but post was not found: " + existingPostId.get()));
        }

        var taggedUsers = new PostTaggedUsers(normalizeSet(command.taggedUsers()));
        var postTags = new PostTags(normalizeSet(command.postTags()));
        var description = new PostDescription(command.description() == null ? "" : command.description());

        validateTaggedUsers(command.currentUserId(), taggedUsers.value());

        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var post = Post.create(
                new PostId(UUID.randomUUID()),
                new UserId(command.currentUserId()),
                description,
                taggedUsers,
                postTags,
                now
        );

        var savedPost = postRepository.save(post);
        postRequestIdempotencyRepository.save(command.correlationId(), savedPost.getId().value());

        var outboxId = UUID.randomUUID();
        var event = postEventMapper.toPostCreatedEvent(outboxId, command.correlationId(), savedPost, now);
        saveOutboxEvent(command.correlationId(), outboxId, event);

        applicationEventPublisher.publishEvent(new PostCreatedDomainEvent(outboxId));

        return toResponse(savedPost);
    }

    private void validateTaggedUsers(UUID creatorUserId, Set<String> taggedUsers) {
        if (taggedUsers.isEmpty()) {
            return;
        }

        Map<String, UUID> userIdsByUsername = taggedUserValidationRepository.findUserIdsByUsernames(taggedUsers);
        for (String taggedUsername : taggedUsers) {
            if (!userIdsByUsername.containsKey(taggedUsername)) {
                throw new TaggedUserNotFoundException(taggedUsername);
            }
        }

        var blockedUserIds = taggedUserValidationRepository.findBlockedUserIds(
                creatorUserId,
                Set.copyOf(userIdsByUsername.values())
        );

        for (Map.Entry<String, UUID> entry : userIdsByUsername.entrySet()) {
            if (blockedUserIds.contains(entry.getValue())) {
                throw new TaggedUserBlockedException(entry.getKey());
            }
        }
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, PostCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(event.occurredAt())
                        .build()
        );
    }

    private PostResponse toResponse(Post post) {
        return new PostResponse(
                post.getId().value(),
                post.getUserId().value(),
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
