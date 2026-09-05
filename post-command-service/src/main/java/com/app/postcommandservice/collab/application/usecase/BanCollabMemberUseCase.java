package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.collab.application.commands.BanCollabMemberCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabMemberBannedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabMemberForbiddenException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.exception.InvalidCollabMemberBanException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberBannedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

@Service
@RequiredArgsConstructor
public class BanCollabMemberUseCase {

    private final CollabMemberRepository collabMemberRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CollabEventMapper collabEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void ban(BanCollabMemberCommand command) {
        var actioningMember = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.actioningUserId())
                .orElseThrow(() -> new CollabMemberForbiddenException(
                        String.format("User %s is not an accepted admin of collab %s",
                                command.actioningUserId(), command.collabId())));
        if (!actioningMember.isAcceptedAdmin()) {
            throw new CollabMemberForbiddenException(
                    String.format("User %s is not an accepted admin of collab %s",
                            command.actioningUserId(), command.collabId()));
        }

        var targetMember = collabMemberRepository.findByCollabIdAndUserId(command.collabId(), command.targetUserId())
                .orElseThrow(() -> new CollabMemberNotFoundException(
                        String.format("Collab member not found for collab %s and user %s",
                                command.collabId(), command.targetUserId())));
        validateTargetMember(targetMember);

        var bannedMember = collabMemberRepository.save(targetMember.ban());
        var correlationId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var occurredAt = Instant.now();
        var event = collabEventMapper.toCollabMemberBannedEvent(outboxId, correlationId, bannedMember, occurredAt);

        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(CollabMemberBannedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
        applicationEventPublisher.publishEvent(new CollabMemberBannedDomainEvent(outboxId));
    }

    private void validateTargetMember(CollabMember targetMember) {
        if (targetMember.getRole() == CollabMemberRole.ADMIN) {
            throw new InvalidCollabMemberBanException(
                    String.format("Admins cannot ban admin member %s in collab %s",
                            targetMember.getUserId().value(), targetMember.getCollabId()));
        }
        if (!targetMember.isAccepted()) {
            throw new InvalidCollabMemberBanException(
                    String.format("Collab member %s is not in ACCEPTED status for collab %s",
                            targetMember.getUserId().value(), targetMember.getCollabId()));
        }
    }
}
