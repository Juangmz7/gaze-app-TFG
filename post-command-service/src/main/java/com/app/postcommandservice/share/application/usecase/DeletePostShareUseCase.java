package com.app.postcommandservice.share.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.share.application.commands.DeletePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.domain.events.PostShareDeletedDomainEvent;
import com.app.postcommandservice.share.infrastructure.events.PostShareDeletedEvent;
import com.app.postcommandservice.share.infrastructure.mapper.PostShareEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeletePostShareUseCase {

    private final PostShareRepository postShareRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostShareEventMapper postShareEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void delete(DeletePostShareCommand command) {
        var deletedRows = postShareRepository.deleteByPostIdAndUserId(command.postId(), command.currentUserId());
        if (deletedRows == 0) {
            log.info("Discarding delete post share request because share does not exist for post {} and user {}",
                    command.postId(), command.currentUserId());
            return;
        }

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = postShareEventMapper.toPostShareDeletedEvent(
                outboxId,
                correlationId,
                command.postId(),
                command.currentUserId(),
                occurredAt
        );
        saveOutboxEvent(outboxId, correlationId, event);

        applicationEventPublisher.publishEvent(new PostShareDeletedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostShareDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostShareDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
