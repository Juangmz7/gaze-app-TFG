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

import com.app.postcommandservice.post.application.commands.UpdatePostCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostUpdatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.events.PostUpdatedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class UpdatePostUseCase {

    private final PostRepository postRepository;
    private final TaggedUserValidationRepository taggedUserValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostEventMapper postEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public PostResponse updatePost(UpdatePostCommand command) {
        var existingPost = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertOwnership(existingPost, command.currentUserId());

        var updateResult = existingPost.update(
                new PostDescription(command.description() == null ? "" : command.description()),
                new PostTaggedUsers(normalizeSet(command.taggedUsers())),
                new PostTags(normalizeSet(command.postTags()))
        );

        if (!updateResult.changed()) {
            return toResponse(existingPost);
        }

        validateTaggedUsers(command.currentUserId(), updateResult.newlyTaggedUsers());

        var savedPost = postRepository.saveAndFlush(updateResult.post());

        var outboxId = UUID.randomUUID();
        var eventCorrelationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = postEventMapper.toPostUpdatedEvent(outboxId, eventCorrelationId, savedPost, occurredAt);
        saveOutboxEvent(outboxId, event);

        applicationEventPublisher.publishEvent(new PostUpdatedDomainEvent(outboxId));

        return toResponse(savedPost);
    }

    private void assertOwnership(Post post, UUID currentUserId) {
        if (!post.getUserId().value().equals(currentUserId)) {
            throw new PostOwnershipException(post.getId().value(), currentUserId);
        }
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

    private void saveOutboxEvent(UUID outboxId, PostUpdatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(event.correlationId())
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostUpdatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
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
