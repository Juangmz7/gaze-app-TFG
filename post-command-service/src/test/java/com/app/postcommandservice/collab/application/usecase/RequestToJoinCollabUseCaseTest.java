package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinRequestCreatedEvent;
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
class RequestToJoinCollabUseCaseTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID CREATOR_ID = UUID.randomUUID();
    private static final UUID REQUESTER_ID = UUID.randomUUID();

    @Mock
    private CollabRepository collabRepository;

    @Mock
    private CollabMemberRepository collabMemberRepository;

    @Mock
    private CollabJoinValidationRepository collabJoinValidationRepository;

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
    private RequestToJoinCollabUseCase requestToJoinCollabUseCase;

    @Test
    void shouldCreatePendingMemberAndPublishEventWhenValidAndNoBlocksExist() {
        var collab = openCollab(CREATOR_ID);
        var savedMember = persistedMember(REQUESTER_ID, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);
        var event = CollabJoinRequestCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .userId(REQUESTER_ID)
                .status(CollabMemberStatus.PENDING)
                .role(CollabMemberRole.MEMBER)
                .createdAt(savedMember.getCreatedAt())
                .build();

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, REQUESTER_ID)).thenReturn(Optional.empty());
        when(collabMemberRepository.findUserIdsByCollabId(COLLAB_ID)).thenReturn(Set.of(CREATOR_ID, UUID.randomUUID()));
        when(collabJoinValidationRepository.findBlockedUserIds(eq(REQUESTER_ID), any(Set.class))).thenReturn(Set.of());
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(savedMember);
        when(collabEventMapper.toCollabJoinRequestCreatedEvent(any(UUID.class), any(UUID.class), eq(savedMember), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID));

        assertThat(response).isEqualTo(new CollabMemberResponse(
                COLLAB_ID,
                REQUESTER_ID,
                CollabMemberStatus.PENDING,
                CollabMemberRole.MEMBER,
                savedMember.getCreatedAt()
        ));

        verify(collabMemberRepository).save(any(CollabMember.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabJoinRequestCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabJoinRequestCreatedDomainEvent.class));
    }

    @Test
    void shouldReturnExistingRequestIdempotentlyWithoutDuplicating() {
        var collab = openCollab(CREATOR_ID);
        var existingMember = persistedMember(REQUESTER_ID, CollabMemberStatus.PENDING, CollabMemberRole.MEMBER);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, REQUESTER_ID)).thenReturn(Optional.of(existingMember));

        var response = requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID));

        assertThat(response.status()).isEqualTo(CollabMemberStatus.PENDING);
        assertThat(response.role()).isEqualTo(CollabMemberRole.MEMBER);
        verify(collabJoinValidationRepository, never()).findBlockedUserIds(any(UUID.class), any(Set.class));
        verify(collabMemberRepository, never()).save(any(CollabMember.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowSpecificExceptionWhenRequesterIsTheCreatorOfTheCollab() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab(REQUESTER_ID)));

        assertThatThrownBy(() -> requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID)))
                .isInstanceOf(CollabJoinRequestCreatorException.class)
                .hasMessageContaining(COLLAB_ID.toString());
    }

    @Test
    void shouldThrowSpecificExceptionWhenABlockRelationExistsWithAnyCurrentMember() {
        var blockedMemberId = UUID.randomUUID();

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab(CREATOR_ID)));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, REQUESTER_ID)).thenReturn(Optional.empty());
        when(collabMemberRepository.findUserIdsByCollabId(COLLAB_ID)).thenReturn(Set.of(CREATOR_ID, blockedMemberId));
        when(collabJoinValidationRepository.findBlockedUserIds(REQUESTER_ID, Set.of(CREATOR_ID, blockedMemberId)))
                .thenReturn(Set.of(blockedMemberId));

        assertThatThrownBy(() -> requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID)))
                .isInstanceOf(CollabJoinRequestBlockedException.class)
                .hasMessageContaining(blockedMemberId.toString());
    }

    @Test
    void shouldThrowSpecificExceptionWhenCollabIsClosed() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(closedCollab()));

        assertThatThrownBy(() -> requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID)))
                .isInstanceOf(CollabNotOpenException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void shouldThrowSpecificExceptionWhenCollabDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> requestToJoinCollabUseCase.request(new RequestToJoinCollabCommand(COLLAB_ID, REQUESTER_ID)))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());
    }

    private Collab openCollab(UUID creatorId) {
        return new Collab(
                COLLAB_ID,
                new CollabTitle("Collab"),
                new UserId(creatorId),
                ColabStatus.OPEN,
                Instant.now()
        );
    }

    private Collab closedCollab() {
        return new Collab(
                COLLAB_ID,
                new CollabTitle("Collab"),
                new UserId(CREATOR_ID),
                ColabStatus.CLOSED,
                Instant.now()
        );
    }

    private CollabMember persistedMember(UUID userId, CollabMemberStatus status, CollabMemberRole role) {
        return new CollabMember(COLLAB_ID, new UserId(userId), status, role, Instant.now());
    }
}
