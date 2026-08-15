package com.app.postcommandservice.view.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

@Service
@RequiredArgsConstructor
public class DispatchProcessPostViewCommandUseCase {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void dispatch(
            UUID viewId,
            UUID postId,
            UUID userId,
            PostViewSource source,
            int feedPosition,
            int durationMs,
            int timeWatchedMs,
            int completionPercent,
            PostViewExitReason exitReason) {
        var command = new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                viewId,
                postId,
                userId,
                source,
                feedPosition,
                durationMs,
                timeWatchedMs,
                completionPercent,
                exitReason
        );

        var outboxId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(command.correlationId())
                .payload(jsonMapper.toJson(command))
                .eventType(ProcessPostViewCommand.class.getSimpleName())
                .status(EventStatus.PENDING)
                .build());

        applicationEventPublisher.publishEvent(new OutboxEventCreatedDomainEvent(outboxId));
    }
}
