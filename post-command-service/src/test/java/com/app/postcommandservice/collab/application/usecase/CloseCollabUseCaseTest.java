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

import com.app.postcommandservice.collab.application.commands.CloseCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabClosedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabClosedEvent;
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
class CloseCollabUseCaseTest {

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
    private CloseCollabUseCase closeCollabUseCase;

    @Test
    void shouldUpdateStatusToClosedAndPublishCollabClosedEventWhenAdminRequestsClosure() {
        var openCollab = collab(ColabStatus.OPEN);
        var closedCollab = collab(ColabStatus.CLOSED);
        var adminMember = member(CollabMemberRole.ADMIN);
        var event = CollabClosedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .title("Close collab")
                .createdBy(USER_ID)
                .closedBy(USER_ID)
                .collabStatus(ColabStatus.CLOSED)
                .collabCreatedAt(Instant.now())
                .build();

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(adminMember));
        when(collabRepository.save(any(Collab.class))).thenReturn(closedCollab);
        when(collabEventMapper.toCollabClosedEvent(any(UUID.class), any(UUID.class), eq(closedCollab), eq(USER_ID),
                any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        boolean closed = closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID));

        assertThat(closed).isTrue();
        verify(collabRepository).save(any(Collab.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabClosedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabClosedDomainEvent.class));
    }

    @Test
    void shouldReturnAcceptedSignalWithoutDatabaseWriteOrEventWhenCollabIsAlreadyClosed() {
        var closedCollab = collab(ColabStatus.CLOSED);
        var adminMember = member(CollabMemberRole.ADMIN);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(closedCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(adminMember));

        boolean closed = closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID));

        assertThat(closed).isFalse();
        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowAccessDeniedWhenRequesterIsNotAnAdminMember() {
        var openCollab = collab(ColabStatus.OPEN);
        var member = member(CollabMemberRole.MEMBER);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowAccessDeniedWhenRequesterHasNoMembershipRow() {
        var openCollab = collab(ColabStatus.OPEN);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowAccessDeniedWhenRequesterMembershipIsLeft() {
        var openCollab = collab(ColabStatus.OPEN);
        var leftAdmin = member(CollabMemberRole.ADMIN, CollabMemberStatus.LEFT);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(leftAdmin));

        assertThatThrownBy(() -> closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowAccessDeniedWhenRequesterMembershipIsBanned() {
        var openCollab = collab(ColabStatus.OPEN);
        var bannedAdmin = member(CollabMemberRole.ADMIN, CollabMemberStatus.BANNED);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(bannedAdmin));

        assertThatThrownBy(() -> closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowCollabNotFoundWhenTargetCollabDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeCollabUseCase.close(new CloseCollabCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabMemberRepository, never()).findByCollabIdAndUserId(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private Collab collab(ColabStatus status) {
        return new Collab(
                COLLAB_ID,
                new CollabTitle("Close collab"),
                new UserId(USER_ID),
                status,
                Instant.now()
        );
    }

    private CollabMember member(CollabMemberRole role) {
        return member(role, CollabMemberStatus.ACCEPTED);
    }

    private CollabMember member(CollabMemberRole role, CollabMemberStatus status) {
        return new CollabMember(
                COLLAB_ID,
                new UserId(USER_ID),
                status,
                role,
                Instant.now()
        );
    }
}
