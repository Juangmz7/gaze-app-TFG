package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.LeaveCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabMemberLeftDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminLeaveNotAllowedException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotActiveException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberLeftEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class LeaveCollabUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void leave(LeaveCollabCommand command) {
        collabRepository.findById(command.collabId())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));

        var existingMember = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId())
                .orElseThrow(() -> new CollabMemberNotFoundException(command.collabId(), command.currentUserId()));

        assertCanLeave(existingMember);

        if (!collabMemberRepository.leaveIfAccepted(command.collabId(), command.currentUserId())) {
            var currentMember = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId())
                    .orElseThrow(() -> new CollabMemberNotFoundException(command.collabId(), command.currentUserId()));
            assertCanLeave(currentMember);
            return;
        }

        var leftMember = existingMember.leave();

        var correlationId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabMemberLeftEvent(outboxId, correlationId, leftMember, occurredAt);
        saveOutboxEvent(correlationId, outboxId, event);

        applicationEventPublisher.publishEvent(new CollabMemberLeftDomainEvent(outboxId));
    }

    private void assertCanLeave(CollabMember collabMember) {
        if (collabMember.getRole() == CollabMemberRole.ADMIN) {
            throw new CollabAdminLeaveNotAllowedException(
                    collabMember.getCollabId(),
                    collabMember.getUserId().value()
            );
        }
        if (collabMember.getCollabMemberStatus() != CollabMemberStatus.ACCEPTED) {
            throw new CollabMemberNotActiveException(
                    collabMember.getCollabId(),
                    collabMember.getUserId().value(),
                    collabMember.getCollabMemberStatus()
            );
        }
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, CollabMemberLeftEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabMemberLeftEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
