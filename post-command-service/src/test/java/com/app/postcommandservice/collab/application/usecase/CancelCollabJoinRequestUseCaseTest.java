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

import com.app.postcommandservice.collab.application.commands.CancelCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinRequestDeletedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabJoinRequestNotPendingException;
import com.app.postcommandservice.collab.domain.exception.CollabMemberNotFoundException;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestDeletedEvent;
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
class CancelCollabJoinRequestUseCaseTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

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
    private CancelCollabJoinRequestUseCase cancelCollabJoinRequestUseCase;

    @Test
    void shouldUpdateStatusToDeletedAndPublishEventWhenRequesterCancelsPendingRequest() {
        var pendingMember = collabMember(USER_ID, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);
        var deletedMember = collabMember(USER_ID, CollabMemberStatus.DELETED, CollabMemberRole.MEMBER);
        var event = CollabJoinRequestDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(USER_ID)
                .deletedBy(USER_ID)
                .collabMemberStatus(CollabMemberStatus.DELETED)
                .role(CollabMemberRole.MEMBER)
                .memberCreatedAt(deletedMember.getCreatedAt())
                .build();

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID))
                .thenReturn(Optional.of(pendingMember), Optional.of(deletedMember));
        when(collabMemberRepository.deletePendingMember(COLLAB_ID, USER_ID)).thenReturn(true);
        when(collabEventMapper.toCollabJoinRequestDeletedEvent(any(UUID.class), any(UUID.class), eq(USER_ID),
                eq(deletedMember), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = cancelCollabJoinRequestUseCase.cancel(new CancelCollabJoinRequestCommand(COLLAB_ID, USER_ID));

        assertThat(response.collabId()).isEqualTo(COLLAB_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.collabMemberStatus()).isEqualTo(CollabMemberStatus.DELETED);
        assertThat(response.role()).isEqualTo(CollabMemberRole.MEMBER);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabJoinRequestDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabJoinRequestDeletedDomainEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = {"ACCEPTED", "REJECTED", "DELETED", "LEFT", "BANNED"})
    void shouldThrowDomainExceptionWhenTheTargetRequestIsNotPending(CollabMemberStatus status) {
        var existingMember = collabMember(USER_ID, status, CollabMemberRole.MEMBER);

        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.of(existingMember));

        assertThatThrownBy(() -> cancelCollabJoinRequestUseCase.cancel(new CancelCollabJoinRequestCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabJoinRequestNotPendingException.class);

        verify(collabMemberRepository, never()).deletePendingMember(COLLAB_ID, USER_ID);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowCollabMemberNotFoundExceptionWhenTheTargetMembershipDoesNotExist() {
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cancelCollabJoinRequestUseCase.cancel(new CancelCollabJoinRequestCommand(COLLAB_ID, USER_ID)))
                .isInstanceOf(CollabMemberNotFoundException.class);

        verify(collabMemberRepository, never()).deletePendingMember(COLLAB_ID, USER_ID);
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
