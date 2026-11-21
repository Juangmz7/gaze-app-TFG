package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.UpdateCollabJoinInstructionsCommand;
import com.app.postcommandservice.collab.application.dto.CollabJoinInstructionsResponse;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinInstructionsUpdatedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabJoinInstructions;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinInstructionsUpdatedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class UpdateCollabJoinInstructionsUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CollabJoinInstructionsResponse update(UpdateCollabJoinInstructionsCommand command) {
        if (command == null || command.collabId() == null || command.currentUserId() == null) {
            throw new IllegalArgumentException("collabId and currentUserId are required");
        }

        // A deleted collab is gone for its members too: nothing left to onboard into
        var collab = collabRepository.findById(command.collabId())
                .filter(existing -> !existing.isDeleted())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));

        var requester = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId())
                .orElseThrow(() -> new CollabAdminAccessDeniedException(command.collabId(), command.currentUserId()));
        if (requester.getRole() != CollabMemberRole.ADMIN
                || requester.getCollabMemberStatus() != CollabMemberStatus.ACCEPTED) {
            throw new CollabAdminAccessDeniedException(command.collabId(), command.currentUserId());
        }

        var joinInstructions = CollabJoinInstructions.ofNullable(command.joinInstructions());
        var updatedCollab = collabRepository.save(collab.updateJoinInstructions(joinInstructions));

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabJoinInstructionsUpdatedEvent(
                outboxId,
                correlationId,
                updatedCollab,
                occurredAt
        );
        saveOutboxEvent(outboxId, correlationId, event);
        applicationEventPublisher.publishEvent(new CollabJoinInstructionsUpdatedDomainEvent(outboxId));

        return new CollabJoinInstructionsResponse(
                CollabJoinInstructions.valueOrNull(updatedCollab.getJoinInstructions())
        );
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabJoinInstructionsUpdatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabJoinInstructionsUpdatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
