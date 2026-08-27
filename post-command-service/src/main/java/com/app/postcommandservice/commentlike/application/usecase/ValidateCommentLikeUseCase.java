package com.app.postcommandservice.commentlike.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.repository.CommentLikeValidationRepository;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeRepository;
import com.app.postcommandservice.commentlike.domain.events.PostCommentLikeCreatedDomainEvent;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeContext;
import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeCreatedEvent;
import com.app.postcommandservice.commentlike.infrastructure.mapper.PostCommentLikeEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidateCommentLikeUseCase {

    private final PostCommentLikeRepository postCommentLikeRepository;
    private final CommentLikeValidationRepository commentLikeValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostCommentLikeEventMapper postCommentLikeEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void validateAndCreateLike(ValidateCommentLikeCommand command) {
        var activeComment = commentLikeValidationRepository.findActiveComment(command.postId(), command.commentId());
        if (activeComment.isEmpty()) {
            log.info(
                    "Discarding comment like command {} because comment {} on post {} does not exist or is not ACTIVE",
                    command.id(),
                    command.commentId(),
                    command.postId()
            );
            return;
        }

        var commentOwnerId = activeComment.get().ownerUserId();
        if (commentLikeValidationRepository.existsBlockRelationship(command.userId(), commentOwnerId)) {
            log.info("Discarding comment like command {} because users {} and {} are blocked",
                    command.id(), command.userId(), commentOwnerId);
            return;
        }

        if (postCommentLikeRepository.existsByCommentIdAndUserId(command.commentId(), command.userId())) {
            log.info("Discarding comment like command {} because like already exists for comment {} and user {}",
                    command.id(), command.commentId(), command.userId());
            return;
        }

        var savedLike = postCommentLikeRepository.save(PostCommentLike.create(
                new CommentId(command.commentId()),
                new UserId(command.userId()),
                new CommentLikeContext(command.source(), command.feedPosition())
        ));

        var outboxId = UUID.randomUUID();
        var event = postCommentLikeEventMapper.toPostCommentLikeCreatedEvent(
                outboxId,
                command.correlationId(),
                command.postId(),
                savedLike,
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostCommentLikeCreatedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostCommentLikeCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostCommentLikeCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
