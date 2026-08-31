package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.RequestToJoinCollabCommand;
import com.app.postcommandservice.collab.application.dto.CollabMemberResponse;
import com.app.postcommandservice.collab.application.repository.CollabJoinValidationRepository;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestCreatedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestBlockedException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestCreatorException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotOpenException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestCreatedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class RequestToJoinCollabUseCase {

    private final CollabRepository collabRepository;
    private final CollabMemberRepository collabMemberRepository;
    private final CollabJoinValidationRepository collabJoinValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public CollabMemberResponse request(RequestToJoinCollabCommand command) {
        var collab = collabRepository.findById(command.collabId())
                .orElseThrow(() -> new CollabNotFoundException(command.collabId()));

        validateCollabCanReceiveRequest(
                collab.getId(),
                collab.getCollabStatus(),
                collab.getCreatedBy().value(),
                command.currentUserId()
        );

        var existingMember = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.currentUserId());
        if (existingMember.isPresent()) {
            return toResponse(existingMember.get());
        }

        validateNoBlockedMembers(command.collabId(), command.currentUserId());

        var savedMember = collabMemberRepository.save(
                CollabMember.createPendingMember(command.collabId(), new UserId(command.currentUserId()))
        );

        var outboxId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = collabEventMapper.toCollabJoinRequestCreatedEvent(outboxId, correlationId, savedMember, occurredAt);
        saveOutboxEvent(outboxId, correlationId, event);
        applicationEventPublisher.publishEvent(new CollabJoinRequestCreatedDomainEvent(outboxId));

        return toResponse(savedMember);
    }

    private void validateCollabCanReceiveRequest(
            UUID collabId,
            ColabStatus collabStatus,
            UUID collabCreatorId,
            UUID requesterUserId) {
        if (collabStatus != ColabStatus.OPEN) {
            throw new CollabNotOpenException(collabId, collabStatus);
        }
        if (collabCreatorId.equals(requesterUserId)) {
            throw new CollabJoinRequestCreatorException(collabId);
        }
    }

    private void validateNoBlockedMembers(UUID collabId, UUID requesterUserId) {
        Set<UUID> memberUserIds = collabMemberRepository.findUserIdsByCollabId(collabId);
        var blockedUserIds = collabJoinValidationRepository.findBlockedUserIds(requesterUserId, memberUserIds);
        if (!blockedUserIds.isEmpty()) {
            throw new CollabJoinRequestBlockedException(collabId, blockedUserIds.iterator().next());
        }
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, CollabJoinRequestCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabJoinRequestCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private CollabMemberResponse toResponse(CollabMember member) {
        return new CollabMemberResponse(
                member.getCollabId(),
                member.getUserId().value(),
                member.getCollabMemberStatus(),
                member.getRole(),
                member.getCreatedAt()
        );
    }
}
