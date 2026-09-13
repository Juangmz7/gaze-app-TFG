package com.app.postcommandservice.comment.application.usecase;

import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.repository.CommentRelationshipValidationRepository;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.application.repository.CommentRequestIdempotencyRepository;
import com.app.postcommandservice.comment.domain.exception.CommentBlockedException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.events.CommentCreatedDomainEvent;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.infrastructure.events.CommentCreatedEvent;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentEventMapper;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class CreateCommentUseCase {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final CommentRequestIdempotencyRepository commentRequestIdempotencyRepository;
    private final CommentRelationshipValidationRepository commentRelationshipValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CommentEventMapper commentEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CommentResponse createComment(CreateCommentCommand command) {
        commentRequestIdempotencyRepository.acquireCorrelationLock(command.correlationId());

        var existingCommentId = commentRequestIdempotencyRepository.findCommentIdByCorrelationId(command.correlationId());
        if (existingCommentId.isPresent()) {
            return commentRepository.findById(existingCommentId.get())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record exists but comment was not found: " + existingCommentId.get()));
        }

        var post = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        validatePostIsActive(post.getId().value(), post.getStatus());
        validateBlockedRelationship(command.currentUserId(), post.getUserId().value(), "post owner");
        validateReplyTarget(command.postId(), command.currentUserId(), command.replyTo());

        var comment = Comment.create(
                new CommentId(UUID.randomUUID()),
                new PostId(command.postId()),
                new UserId(command.currentUserId()),
                new CommentContent(command.content()),
                command.replyTo()
        );

        var savedComment = commentRepository.save(comment);
        commentRequestIdempotencyRepository.save(command.correlationId(), savedComment.getId().value());

        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = commentEventMapper.toCommentCreatedEvent(
                outboxId,
                command.correlationId(),
                savedComment,
                occurredAt
        );
        saveOutboxEvent(command.correlationId(), outboxId, event);
        applicationEventPublisher.publishEvent(new CommentCreatedDomainEvent(outboxId));

        return toResponse(savedComment);
    }

    private void validatePostIsActive(UUID postId, PostStatus status) {
        if (status != PostStatus.ACTIVE) {
            throw new PostNotActiveException(postId, status);
        }
    }

    private void validateReplyTarget(UUID postId, UUID currentUserId, UUID replyTo) {
        if (replyTo == null) {
            return;
        }

        var parentComment = commentRepository.findByIdAndPostId(replyTo, postId)
                .orElseThrow(() -> new CommentNotFoundException(replyTo));

        validateBlockedRelationship(currentUserId, parentComment.getUserId().value(), "parent comment author");
    }

    private void validateBlockedRelationship(UUID requesterUserId, UUID targetUserId, String target) {
        if (requesterUserId.equals(targetUserId)) {
            return;
        }
        if (commentRelationshipValidationRepository.existsBlockRelationship(requesterUserId, targetUserId)) {
            throw new CommentBlockedException(target);
        }
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, CommentCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CommentCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId().value(),
                comment.getPostId().value(),
                comment.getUserId().value(),
                comment.getContent().value(),
                comment.getReplyTo(),
                comment.getUpdatedAt(),
                comment.getCreatedAt()
        );
    }
}
