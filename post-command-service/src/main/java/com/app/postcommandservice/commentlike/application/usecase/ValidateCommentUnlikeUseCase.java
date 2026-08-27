package com.app.postcommandservice.commentlike.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeRepository;
import com.app.postcommandservice.commentlike.domain.events.PostCommentLikeDeletedDomainEvent;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeDeletedEvent;
import com.app.postcommandservice.commentlike.infrastructure.mapper.PostCommentLikeEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidateCommentUnlikeUseCase {

    private final PostCommentLikeRepository postCommentLikeRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostCommentLikeEventMapper postCommentLikeEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void validateAndDeleteLike(ValidateCommentUnlikeCommand command) {
        if (!postCommentLikeRepository.existsByCommentIdAndUserId(command.commentId(), command.userId())) {
            log.info("Discarding comment unlike command {} because like does not exist for comment {} and user {}",
                    command.id(), command.commentId(), command.userId());
            return;
        }

        postCommentLikeRepository.deleteByCommentIdAndUserId(command.commentId(), command.userId());

        var outboxId = UUID.randomUUID();
        var event = postCommentLikeEventMapper.toPostCommentLikeDeletedEvent(
                outboxId,
                command.correlationId(),
                command.commentId(),
                command.userId(),
                command.source(),
                command.feedPosition(),
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostCommentLikeDeletedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostCommentLikeDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostCommentLikeDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
