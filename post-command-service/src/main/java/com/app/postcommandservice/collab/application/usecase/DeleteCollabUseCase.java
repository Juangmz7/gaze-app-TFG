package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.DeleteCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabDeletedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabDeleteForbiddenException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class DeleteCollabUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void delete(DeleteCollabCommand command) {
        var existingCollab = collabRepository.findById(command.collabId())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));

        assertAcceptedAdminMembership(command.collabId(), command.currentUserId());

        if (existingCollab.isDeleted()) {
            return;
        }

        var deletedCollab = collabRepository.save(existingCollab.delete());
        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabDeletedEvent(
                outboxId,
                correlationId,
                deletedCollab.getId(),
                command.currentUserId(),
                occurredAt
        );
        saveOutboxEvent(outboxId, correlationId, event);

        applicationEventPublisher.publishEvent(new CollabDeletedDomainEvent(outboxId));
    }

    private void assertAcceptedAdminMembership(UUID collabId, UUID currentUserId) {
        var collabMember = collabMemberRepository.findByCollabIdAndUserId(collabId, currentUserId)
                .orElseThrow(() -> new CollabDeleteForbiddenException(collabId, currentUserId));
        if (collabMember.getCollabMemberStatus() != CollabMemberStatus.ACCEPTED
                || collabMember.getRole() != CollabMemberRole.ADMIN) {
            throw new CollabDeleteForbiddenException(collabId, currentUserId);
        }
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
