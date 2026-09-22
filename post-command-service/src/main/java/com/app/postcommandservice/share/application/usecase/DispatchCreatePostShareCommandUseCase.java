package com.app.postcommandservice.share.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class DispatchCreatePostShareCommandUseCase {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void dispatch(UUID postId, UUID userId) {
        var command = new CreatePostShareCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                postId,
                userId
        );

        var outboxId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(command.correlationId())
                .payload(jsonMapper.toJson(command))
                .eventType(CreatePostShareCommand.class.getSimpleName())
                .status(EventStatus.PENDING)
                .build());

        applicationEventPublisher.publishEvent(new OutboxEventCreatedDomainEvent(outboxId));
    }
}
