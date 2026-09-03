package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.CloseCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabClosedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabClosedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class CloseCollabUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public boolean close(CloseCollabCommand command) {
        var collab = collabRepository.findById(command.collabId())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));

        var requester = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId())
                .orElseThrow(() -> new CollabAccessDeniedException(command.collabId(), command.currentUserId()));
        if (requester.getRole() != CollabMemberRole.ADMIN
                || requester.getCollabMemberStatus() != CollabMemberStatus.ACCEPTED) {
            throw new CollabAccessDeniedException(command.collabId(), command.currentUserId());
        }

        if (collab.getCollabStatus() == ColabStatus.CLOSED) {
            return false;
        }

        var closedCollab = collabRepository.save(collab.close());
        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabClosedEvent(
                outboxId,
                correlationId,
                closedCollab,
                command.currentUserId(),
                occurredAt
        );
        saveOutboxEvent(outboxId, correlationId, event);
        applicationEventPublisher.publishEvent(new CollabClosedDomainEvent(outboxId));
        return true;
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabClosedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabClosedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
