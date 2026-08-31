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

import com.app.postcommandservice.collab.application.commands.LeaveCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabMemberLeftDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminLeaveNotAllowedException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotActiveException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabMemberLeftEvent;
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
class LeaveCollabUseCaseTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private CollabRepository collabRepository;

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
    private LeaveCollabUseCase leaveCollabUseCase;

    @Test
    void shouldUpdateStatusToLeftAndPublishEventWhenUserLeavesCollab() {
        var collab = persistedCollab();
        var existingMember = persistedMember(CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        var leftMember = existingMember.leave();
        var event = CollabMemberLeftEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(USER_ID)
                .collabMemberStatus(CollabMemberStatus.LEFT)
                .role(CollabMemberRole.MEMBER)
                .createdAt(existingMember.getCreatedAt())
                .build();

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(existingMember));
        when(collabMemberRepository.leaveIfAccepted(COLLAB_ID, USER_ID)).thenReturn(true);
        when(collabEventMapper.toCollabMemberLeftEvent(any(UUID.class), any(UUID.class), any(CollabMember.class), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID));

        verify(collabMemberRepository).leaveIfAccepted(COLLAB_ID, USER_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabMemberLeftEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabMemberLeftDomainEvent.class));
    }

    @Test
    void shouldNotPublishEventWhenConcurrentLeaveAlreadyChangedStatus() {
        var existingMember = persistedMember(CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER);
        var currentMember = persistedMember(CollabMemberStatus.LEFT, CollabMemberRole.MEMBER);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab()));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID))
                .thenReturn(Optional.of(existingMember), Optional.of(currentMember));
        when(collabMemberRepository.leaveIfAccepted(COLLAB_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabMemberNotActiveException.class)
                .hasMessageContaining("LEFT");

        verify(collabMemberRepository).leaveIfAccepted(COLLAB_ID, USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CollabMemberLeftDomainEvent.class));
    }

    @Test
    void shouldThrowExceptionWhenCollabDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabMemberRepository, never()).findByCollabIdAndUserId(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowExceptionWhenMemberDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab()));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabMemberNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString())
                .hasMessageContaining(USER_ID.toString());

        verify(collabMemberRepository, never()).leaveIfAccepted(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowExceptionWhenAdminAttemptsToLeave() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab()));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID))
                .thenReturn(Optional.of(persistedMember(CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN)));

        assertThatThrownBy(() -> leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabAdminLeaveNotAllowedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabMemberRepository, never()).leaveIfAccepted(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowExceptionWhenMemberIsNotAccepted() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab()));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID))
                .thenReturn(Optional.of(persistedMember(CollabMemberStatus.LEFT, CollabMemberRole.MEMBER)));

        assertThatThrownBy(() -> leaveCollabUseCase.leave(new LeaveCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabMemberNotActiveException.class)
                .hasMessageContaining("ACCEPTED")
                .hasMessageContaining("LEFT");

        verify(collabMemberRepository, never()).leaveIfAccepted(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private Collab persistedCollab() {
        return new Collab(
                COLLAB_ID,
                new CollabTitle("Collab"),
                new UserId(UUID.randomUUID()),
                ColabStatus.OPEN,
                Instant.now()
        );
    }

    private CollabMember persistedMember(CollabMemberStatus status, CollabMemberRole role) {
        return new CollabMember(
                COLLAB_ID,
                new UserId(USER_ID),
                status,
                role,
                Instant.now()
        );
    }
}
