package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.collab.application.commands.DeclineCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestDeclinedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeclinedEvent;
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
class DeclineCollabJoinRequestUseCaseTest {

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
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private DeclineCollabJoinRequestUseCase declineCollabJoinRequestUseCase;

    @Test
    void shouldUpdateStatusToRejectedAndPublishEventWhenAdminDeclinesPendingRequest() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        var pendingMember = collabMember(TARGET_USER_ID, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);
        var rejectedMember = collabMember(TARGET_USER_ID, CollabMemberStatus.REJECTED, CollabMemberRole.MEMBER);
        var event = CollabJoinRequestDeclinedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(TARGET_USER_ID)
                .declinedBy(ACTIONING_USER_ID)
                .collabMemberStatus(CollabMemberStatus.REJECTED)
                .role(CollabMemberRole.MEMBER)
                .memberCreatedAt(rejectedMember.getCreatedAt())
                .build();

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(pendingMember), Optional.of(rejectedMember));
        when(collabMemberRepository.rejectPendingMember(COLLAB_ID, TARGET_USER_ID)).thenReturn(true);
        when(collabEventMapper.toCollabJoinRequestDeclinedEvent(any(UUID.class), any(UUID.class), eq(ACTIONING_USER_ID),
                any(CollabMember.class), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        );

        assertThat(response.collabId()).isEqualTo(COLLAB_ID);
        assertThat(response.userId()).isEqualTo(TARGET_USER_ID);
        assertThat(response.collabMemberStatus()).isEqualTo(CollabMemberStatus.REJECTED);
        assertThat(response.role()).isEqualTo(CollabMemberRole.MEMBER);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabJoinRequestDeclinedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabJoinRequestDeclinedDomainEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenTheActioningUserIsNotAnAdmin() {
        var member = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestAccessDeniedException.class);

        verify(collabMemberRepository, never()).rejectPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenTheActioningMembershipDoesNotExist() {
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestAccessDeniedException.class);

        verify(collabMemberRepository, never()).rejectPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = {"PENDING", "LEFT", "BANNED"})
    void shouldThrowDomainExceptionWhenTheActioningUserIsNotAccepted(CollabMemberStatus status) {
        var member = collabMember(ACTIONING_USER_ID, status, CollabMemberRole.ADMIN);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestAccessDeniedException.class);

        verify(collabMemberRepository, never()).rejectPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenTheTargetRequestIsNotPending() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        var acceptedMember = collabMember(TARGET_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID)).thenReturn(Optional.of(acceptedMember));

        assertThatThrownBy(() -> declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestNotPendingException.class);

        verify(collabMemberRepository, never()).rejectPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowCollabMemberNotFoundExceptionWhenTheTargetMembershipDoesNotExist() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> declineCollabJoinRequestUseCase.decline(
                new DeclineCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabMemberNotFoundException.class);

        verify(collabMemberRepository, never()).rejectPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private CollabMember collabMember(UUID userId, CollabMemberStatus status, CollabMemberRole role) {
        return new CollabMember(
                COLLAB_ID,
                new UserId(userId),
                status,
                role,
                Instant.now()
        );
    }
}
