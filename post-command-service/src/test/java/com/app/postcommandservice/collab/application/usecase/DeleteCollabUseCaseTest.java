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

import com.app.postcommandservice.collab.application.commands.DeleteCollabCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabDeletedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabDeleteForbiddenException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabDeletedEvent;
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
class DeleteCollabUseCaseTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

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
    private DeleteCollabUseCase deleteCollabUseCase;

    @Test
    void shouldSoftDeleteCollabAndPublishEventWhenRequesterIsAnAcceptedAdmin() {
        var existingCollab = persistedCollab(ColabStatus.OPEN);
        var deletedCollab = persistedCollab(ColabStatus.DELETED);
        var acceptedAdmin = persistedMember(ADMIN_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);
        var event = CollabDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .collabId(COLLAB_ID)
                .actionedBy(ADMIN_ID)
                .occurredAt(Instant.now())
                .build();

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(existingCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID)).thenReturn(Optional.of(acceptedAdmin));
        when(collabRepository.save(any(Collab.class))).thenReturn(deletedCollab);
        when(collabEventMapper.toCollabDeletedEvent(any(UUID.class), any(UUID.class), eq(COLLAB_ID), eq(ADMIN_ID),
                any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{json}");

        deleteCollabUseCase.delete(new DeleteCollabCommand(COLLAB_ID, ADMIN_ID));

        verify(collabRepository).save(any(Collab.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{json}");
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabDeletedDomainEvent.class));
    }

    @Test
    void shouldReturnWithoutSavingOrPublishingWhenCollabIsAlreadyDeleted() {
        var deletedCollab = persistedCollab(ColabStatus.DELETED);
        var acceptedAdmin = persistedMember(ADMIN_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN);

        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(deletedCollab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID)).thenReturn(Optional.of(acceptedAdmin));

        deleteCollabUseCase.delete(new DeleteCollabCommand(COLLAB_ID, ADMIN_ID));

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CollabDeletedDomainEvent.class));
    }

    @Test
    void shouldThrowForbiddenWhenRequesterIsNotAnAdmin() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab(ColabStatus.OPEN)));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID))
                .thenReturn(Optional.of(persistedMember(ADMIN_ID, CollabMemberStatus.ACCEPTED, CollabMemberRole.MEMBER)));

        assertThatThrownBy(() -> deleteCollabUseCase.delete(new DeleteCollabCommand(COLLAB_ID, ADMIN_ID)))
                .isInstanceOf(CollabDeleteForbiddenException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowForbiddenWhenRequesterIsNotAMember() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(persistedCollab(ColabStatus.OPEN)));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deleteCollabUseCase.delete(new DeleteCollabCommand(COLLAB_ID, ADMIN_ID)))
                .isInstanceOf(CollabDeleteForbiddenException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowNotFoundWhenCollabDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deleteCollabUseCase.delete(new DeleteCollabCommand(COLLAB_ID, ADMIN_ID)))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabMemberRepository, never()).findByCollabIdAndUserId(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private Collab persistedCollab(ColabStatus status) {
        return new Collab(
                COLLAB_ID,
                new CollabTitle("Delete me"),
                new UserId(UUID.randomUUID()),
                status,
                Instant.now()
        );
    }

    private CollabMember persistedMember(UUID userId, CollabMemberStatus status, CollabMemberRole role) {
        return new CollabMember(
                COLLAB_ID,
                new UserId(userId),
                status,
                role,
                Instant.now()
        );
    }
}
