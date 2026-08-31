package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.collab.application.commands.BanCollabMemberCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabMemberBannedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabMemberForbiddenException;
import com.app.postcommandservice.collab.domain.exception.InvalidCollabMemberBanException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberBannedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BanCollabMemberUseCaseTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID ACTIONING_USER_ID = UUID.randomUUID();
    private static final UUID TARGET_USER_ID = UUID.randomUUID();

    @Mock
    private CollabMemberRepository collabMemberRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private CollabEventMapper collabEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<CollabMember> collabMemberCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<CollabMemberBannedDomainEvent> domainEventCaptor;

    @InjectMocks
    private BanCollabMemberUseCase banCollabMemberUseCase;

    @Test
    void shouldUpdateStatusToBannedAndPublishEventWhenAdminBansAUser() {
        var actioningMember = persistedMember(ACTIONING_USER_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        var targetMember = persistedMember(TARGET_USER_ID, CollabMemberRole.MEMBER, CollabMemberStatus.ACCEPTED);
        var bannedMember = persistedMember(TARGET_USER_ID, CollabMemberRole.MEMBER, CollabMemberStatus.BANNED);
        var event = CollabMemberBannedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(TARGET_USER_ID)
                .collabMemberStatus(CollabMemberStatus.BANNED)
                .role(CollabMemberRole.MEMBER)
                .memberCreatedAt(targetMember.getCreatedAt())
                .build();

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID))
                .thenReturn(Optional.of(actioningMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(targetMember));
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(bannedMember);
        when(collabEventMapper.toCollabMemberBannedEvent(any(UUID.class), any(UUID.class), eq(bannedMember), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{json}");

        banCollabMemberUseCase.ban(new BanCollabMemberCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID));

        verify(collabMemberRepository).save(collabMemberCaptor.capture());
        assertThat(collabMemberCaptor.getValue().getCollabMemberStatus()).isEqualTo(CollabMemberStatus.BANNED);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabMemberBannedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{json}");
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isNotNull();
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxEventCaptor.getValue().getId());
    }

    @Test
    void shouldThrowDomainExceptionWhenAnAdminTriesToBanAnotherAdmin() {
        var actioningMember = persistedMember(ACTIONING_USER_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        var targetAdmin = persistedMember(TARGET_USER_ID, CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID))
                .thenReturn(Optional.of(actioningMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> banCollabMemberUseCase.ban(
                new BanCollabMemberCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)))
                .isInstanceOf(InvalidCollabMemberBanException.class)
                .hasMessageContaining("cannot ban admin");

        verify(collabMemberRepository, never()).save(any(CollabMember.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CollabMemberBannedDomainEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenAUserTriesToExecuteABan() {
        var nonAdminMember = persistedMember(ACTIONING_USER_ID, CollabMemberRole.MEMBER, CollabMemberStatus.ACCEPTED);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID))
                .thenReturn(Optional.of(nonAdminMember));

        assertThatThrownBy(() -> banCollabMemberUseCase.ban(
                new BanCollabMemberCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)))
                .isInstanceOf(CollabMemberForbiddenException.class)
                .hasMessageContaining("accepted admin");

        verify(collabMemberRepository, never()).save(any(CollabMember.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CollabMemberBannedDomainEvent.class));
    }

    private CollabMember persistedMember(UUID userId, CollabMemberRole role, CollabMemberStatus status) {
        return new CollabMember(
                COLLAB_ID,
                new UserId(userId),
                status,
                role,
                Instant.now()
        );
    }
}
