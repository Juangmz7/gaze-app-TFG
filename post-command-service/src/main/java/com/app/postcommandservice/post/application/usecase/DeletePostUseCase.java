package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.commands.DeletePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.events.PostDeletedDomainEvent;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.infrastructure.events.PostDeletedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class DeletePostUseCase {

    private final PostRepository postRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostEventMapper postEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void deletePost(DeletePostCommand command) {
        var existingPost = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertOwnership(existingPost, command.currentUserId());

        var deletedPost = postRepository.saveAndFlush(existingPost.delete());

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = postEventMapper.toPostDeletedEvent(
                outboxId,
                correlationId,
                deletedPost.getId().value(),
                deletedPost.getUserId().value(),
                occurredAt
        );
        saveOutboxEvent(outboxId, correlationId, event);

        applicationEventPublisher.publishEvent(new PostDeletedDomainEvent(outboxId));
    }

    private void assertOwnership(Post post, UUID currentUserId) {
        if (!post.getUserId().value().equals(currentUserId)) {
            throw new PostOwnershipException(post.getId().value(), currentUserId);
        }
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
