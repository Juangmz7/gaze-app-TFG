package com.app.postcommandservice.comment.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.DeleteCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.events.CommentDeletedDomainEvent;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.infrastructure.events.CommentDeletedEvent;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class DeleteCommentUseCase {

    private final CommentRepository commentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CommentEventMapper commentEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void deleteComment(DeleteCommentCommand command) {
        var existingComment = commentRepository.findByIdAndPostId(command.commentId(), command.postId())
                .orElseThrow(() -> new CommentNotFoundException(command.commentId()));

        assertOwnership(existingComment, command.currentUserId());

        var deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var deletedComment = commentRepository.saveAndFlush(existingComment.delete(deletedAt));

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var event = commentEventMapper.toCommentDeletedEvent(
                outboxId,
                correlationId,
                deletedComment.getId().value(),
                deletedComment.getPostId().value(),
                deletedComment.getUserId().value(),
                deletedAt
        );
        saveOutboxEvent(outboxId, correlationId, event);

        applicationEventPublisher.publishEvent(new CommentDeletedDomainEvent(outboxId));
    }

    private void assertOwnership(Comment comment, UUID currentUserId) {
        if (!comment.getUserId().value().equals(currentUserId)) {
            throw new CommentOwnershipException(comment.getId().value(), currentUserId);
        }
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CommentDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CommentDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
