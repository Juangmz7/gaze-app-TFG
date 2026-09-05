package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.DeclineCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.dto.DeclineCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestDeclinedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeclinedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class DeclineCollabJoinRequestUseCase {

    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public DeclineCollabJoinRequestResponse decline(DeclineCollabJoinRequestCommand command) {
        assertAcceptedAdmin(command.collabId(), command.actioningUserId());

        var targetMember = getTargetMember(command.collabId(), command.targetUserId());
        targetMember.reject();

        if (!collabMemberRepository.rejectPendingMember(command.collabId(), command.targetUserId())) {
            var latestTargetMember = getTargetMember(command.collabId(), command.targetUserId());
            throw new CollabJoinRequestNotPendingException(
                    command.collabId(),
                    command.targetUserId(),
                    latestTargetMember.getCollabMemberStatus(),
                    "decline"
            );
        }
        var rejectedMember = getTargetMember(command.collabId(), command.targetUserId());

        var correlationId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabJoinRequestDeclinedEvent(
                outboxId,
                correlationId,
                command.actioningUserId(),
                rejectedMember,
                occurredAt
        );

        saveOutboxEvent(outboxId, correlationId, event);
        applicationEventPublisher.publishEvent(new CollabJoinRequestDeclinedDomainEvent(outboxId));

        return new DeclineCollabJoinRequestResponse(
                rejectedMember.getCollabId(),
                rejectedMember.getUserId().value(),
                rejectedMember.getCollabMemberStatus(),
                rejectedMember.getRole(),
                rejectedMember.getCreatedAt()
        );
    }

    private void assertAcceptedAdmin(UUID collabId, UUID actioningUserId) {
        var actioningMember = collabMemberRepository.findByCollabIdAndUserId(collabId, actioningUserId)
                .orElseThrow(() -> new CollabJoinRequestAccessDeniedException(collabId, actioningUserId));

        if (!actioningMember.isAcceptedAdmin()) {
            throw new CollabJoinRequestAccessDeniedException(collabId, actioningUserId);
        }
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabJoinRequestDeclinedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabJoinRequestDeclinedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private CollabMember getTargetMember(UUID collabId, UUID targetUserId) {
        return collabMemberRepository.findByCollabIdAndUserId(collabId, targetUserId)
                .orElseThrow(() -> new CollabMemberNotFoundException(collabId, targetUserId));
    }
}
