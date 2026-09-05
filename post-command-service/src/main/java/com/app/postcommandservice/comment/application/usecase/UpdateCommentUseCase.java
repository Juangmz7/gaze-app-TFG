package com.app.postcommandservice.comment.application.usecase;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.UpdateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.events.CommentUpdatedDomainEvent;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.infrastructure.events.CommentUpdatedEvent;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class UpdateCommentUseCase {

    private final CommentRepository commentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CommentEventMapper commentEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CommentResponse updateComment(UpdateCommentCommand command) {
        var existingComment = commentRepository.findByIdAndPostId(command.commentId(), command.postId())
                .orElseThrow(() -> new CommentNotFoundException(command.commentId()));

        assertOwnership(existingComment, command.currentUserId());

        var updateResult = existingComment.update(command.content());
        if (!updateResult.changed()) {
            return toResponse(existingComment);
        }

        var savedComment = commentRepository.saveAndFlush(updateResult.comment());

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var event = commentEventMapper.toCommentUpdatedEvent(savedComment);
        saveOutboxEvent(correlationId, outboxId, event);
        applicationEventPublisher.publishEvent(new CommentUpdatedDomainEvent(outboxId));

        return toResponse(savedComment);
    }

    private void assertOwnership(Comment comment, UUID currentUserId) {
        if (!comment.getUserId().value().equals(currentUserId)) {
            throw new CommentOwnershipException(comment.getId().value(), currentUserId);
        }
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, CommentUpdatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CommentUpdatedEvent.class.getSimpleName())
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
