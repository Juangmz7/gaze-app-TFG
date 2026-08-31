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

import com.app.postcommandservice.collab.application.commands.AcceptCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestAcceptedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestAcceptedEvent;
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
class AcceptCollabJoinRequestUseCaseTest {

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
    private AcceptCollabJoinRequestUseCase acceptCollabJoinRequestUseCase;

    @Test
    void shouldUpdateStatusToAcceptedAndPublishEventWhenAdminAcceptsPendingRequest() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        var pendingMember = collabMember(TARGET_USER_ID, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);
        var acceptedMember = collabMember(TARGET_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        var event = CollabJoinRequestAcceptedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(TARGET_USER_ID)
                .acceptedBy(ACTIONING_USER_ID)
                .collabMemberStatus(CollabMemberStatus.ACCEPTED)
                .role(CollabMemberRole.MEMBER)
                .memberCreatedAt(acceptedMember.getCreatedAt())
                .build();

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID))
                .thenReturn(Optional.of(pendingMember), Optional.of(acceptedMember));
        when(collabMemberRepository.acceptPendingMember(COLLAB_ID, TARGET_USER_ID)).thenReturn(true);
        when(collabEventMapper.toCollabJoinRequestAcceptedEvent(any(UUID.class), any(UUID.class), eq(ACTIONING_USER_ID),
                eq(acceptedMember), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = acceptCollabJoinRequestUseCase.accept(
                new AcceptCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        );

        assertThat(response.collabId()).isEqualTo(COLLAB_ID);
        assertThat(response.userId()).isEqualTo(TARGET_USER_ID);
        assertThat(response.collabMemberStatus()).isEqualTo(CollabMemberStatus.ACCEPTED);
        assertThat(response.role()).isEqualTo(CollabMemberRole.MEMBER);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabJoinRequestAcceptedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabJoinRequestAcceptedDomainEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenTheActioningUserIsNotAnAcceptedAdmin() {
        var pendingAdmin = collabMember(ACTIONING_USER_ID, CollabMemberStatus.PENDING, CollabMemberRole.ADMIN);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(pendingAdmin));

        assertThatThrownBy(() -> acceptCollabJoinRequestUseCase.accept(
                new AcceptCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestAccessDeniedException.class);

        verify(collabMemberRepository, never()).acceptPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowDomainExceptionWhenTheTargetRequestIsNotPending() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        var acceptedMember = collabMember(TARGET_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID)).thenReturn(Optional.of(acceptedMember));

        assertThatThrownBy(() -> acceptCollabJoinRequestUseCase.accept(
                new AcceptCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabJoinRequestNotPendingException.class);

        verify(collabMemberRepository, never()).acceptPendingMember(COLLAB_ID, TARGET_USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowCollabMemberNotFoundExceptionWhenTheTargetMembershipDoesNotExist() {
        var adminMember = collabMember(ACTIONING_USER_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ACTIONING_USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, TARGET_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> acceptCollabJoinRequestUseCase.accept(
                new AcceptCollabJoinRequestCommand(COLLAB_ID, TARGET_USER_ID, ACTIONING_USER_ID)
        )).isInstanceOf(CollabMemberNotFoundException.class);

        verify(collabMemberRepository, never()).acceptPendingMember(COLLAB_ID, TARGET_USER_ID);
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
