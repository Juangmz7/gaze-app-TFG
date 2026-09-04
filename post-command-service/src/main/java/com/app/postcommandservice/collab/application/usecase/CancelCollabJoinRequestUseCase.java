package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.CancelCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.dto.CancelCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestDeletedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class CancelCollabJoinRequestUseCase {

    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CancelCollabJoinRequestResponse cancel(CancelCollabJoinRequestCommand command) {
        var targetMember = getTargetMember(command.collabId(), command.userId());
        targetMember.cancelRequest();

        if (!collabMemberRepository.deletePendingMember(command.collabId(), command.userId())) {
            var latestTargetMember = getTargetMember(command.collabId(), command.userId());
            throw new CollabJoinRequestNotPendingException(
                    command.collabId(),
                    command.userId(),
                    latestTargetMember.getCollabMemberStatus(),
                    "cancel"
            );
        }
        var deletedMember = getTargetMember(command.collabId(), command.userId());

        var correlationId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabJoinRequestDeletedEvent(
                outboxId,
                correlationId,
                command.userId(),
                deletedMember,
                occurredAt
        );

        saveOutboxEvent(outboxId, correlationId, event);
        applicationEventPublisher.publishEvent(new CollabJoinRequestDeletedDomainEvent(outboxId));

        return new CancelCollabJoinRequestResponse(
                deletedMember.getCollabId(),
                deletedMember.getUserId().value(),
                deletedMember.getCollabMemberStatus(),
                deletedMember.getRole(),
                deletedMember.getCreatedAt()
        );
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabJoinRequestDeletedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabJoinRequestDeletedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private CollabMember getTargetMember(UUID collabId, UUID userId) {
        return collabMemberRepository.findByCollabIdAndUserId(collabId, userId)
                .orElseThrow(() -> new CollabMemberNotFoundException(collabId, userId));
    }
}
