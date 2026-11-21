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

import com.app.postcommandservice.collab.application.commands.UpdateCollabJoinInstructionsCommand;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabJoinInstructionsUpdatedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabJoinInstructionsTooLongException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabJoinInstructions;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabJoinInstructionsUpdatedEvent;
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
class UpdateCollabJoinInstructionsUseCaseTest {

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
    private ArgumentCaptor<Collab> collabCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<CollabJoinInstructionsUpdatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private UpdateCollabJoinInstructionsUseCase useCase;

    @Test
    void shouldUpdateJoinInstructionsAndPublishCollabJoinInstructionsUpdatedEventWhenAdminSendsAValidText() {
        var updatedCollab = collab(ColabStatus.OPEN, new CollabJoinInstructions("Chat: https://chat"));
        var event = event("Chat: https://chat");
        givenCollab(collab(ColabStatus.OPEN, null));
        givenRequester(CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        when(collabRepository.save(any(Collab.class))).thenReturn(updatedCollab);
        when(collabEventMapper.toCollabJoinInstructionsUpdatedEvent(any(UUID.class), any(UUID.class), eq(updatedCollab),
                any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = useCase.update(command("  Chat: https://chat  "));

        assertThat(response.joinInstructions()).isEqualTo("Chat: https://chat");
        verify(collabRepository).save(collabCaptor.capture());
        assertThat(collabCaptor.getValue().getJoinInstructions().value()).isEqualTo("Chat: https://chat");
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        var outboxEvent = outboxEventCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo(CollabJoinInstructionsUpdatedEvent.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEvent.getPayload()).isEqualTo("{\"event\":\"payload\"}");
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxEvent.getId());
    }

    @Test
    void shouldClearJoinInstructionsAndPublishEventWhenAdminSendsNull() {
        var clearedCollab = collab(ColabStatus.OPEN, null);
        var event = event(null);
        givenCollab(collab(ColabStatus.OPEN, new CollabJoinInstructions("old")));
        givenRequester(CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        when(collabRepository.save(any(Collab.class))).thenReturn(clearedCollab);
        when(collabEventMapper.toCollabJoinInstructionsUpdatedEvent(any(UUID.class), any(UUID.class), eq(clearedCollab),
                any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{}");

        var response = useCase.update(command(null));

        assertThat(response.joinInstructions()).isNull();
        verify(collabRepository).save(collabCaptor.capture());
        assertThat(collabCaptor.getValue().getJoinInstructions()).isNull();
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(applicationEventPublisher).publishEvent(any(CollabJoinInstructionsUpdatedDomainEvent.class));
    }

    @Test
    void shouldStoreNullInsteadOfABlankStringWhenAdminSendsBlankText() {
        var clearedCollab = collab(ColabStatus.OPEN, null);
        givenCollab(collab(ColabStatus.OPEN, new CollabJoinInstructions("old")));
        givenRequester(CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        when(collabRepository.save(any(Collab.class))).thenReturn(clearedCollab);
        when(collabEventMapper.toCollabJoinInstructionsUpdatedEvent(any(UUID.class), any(UUID.class), eq(clearedCollab),
                any(Instant.class))).thenReturn(event(null));
        when(jsonMapper.toJson(any(CollabJoinInstructionsUpdatedEvent.class))).thenReturn("{}");

        var response = useCase.update(command(" \n\t "));

        assertThat(response.joinInstructions()).isNull();
        verify(collabRepository).save(collabCaptor.capture());
        assertThat(collabCaptor.getValue().getJoinInstructions()).isNull();
    }

    @Test
    void shouldAllowTheAdminToUpdateJoinInstructionsOfAClosedCollab() {
        var updatedCollab = collab(ColabStatus.CLOSED, new CollabJoinInstructions("still here"));
        givenCollab(collab(ColabStatus.CLOSED, null));
        givenRequester(CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);
        when(collabRepository.save(any(Collab.class))).thenReturn(updatedCollab);
        when(collabEventMapper.toCollabJoinInstructionsUpdatedEvent(any(UUID.class), any(UUID.class), eq(updatedCollab),
                any(Instant.class))).thenReturn(event("still here"));
        when(jsonMapper.toJson(any(CollabJoinInstructionsUpdatedEvent.class))).thenReturn("{}");

        assertThat(useCase.update(command("still here")).joinInstructions()).isEqualTo("still here");
    }

    @Test
    void shouldThrowDomainExceptionJoinInstructionsTooLongWhenTextExceeds4000Characters() {
        givenCollab(collab(ColabStatus.OPEN, null));
        givenRequester(CollabMemberRole.ADMIN, CollabMemberStatus.ACCEPTED);

        assertThatThrownBy(() -> useCase.update(command("a".repeat(4001))))
                .isInstanceOf(CollabJoinInstructionsTooLongException.class)
                .hasMessageContaining("JOIN_INSTRUCTIONS_TOO_LONG");

        assertNoSideEffects();
    }

    @Test
    void shouldThrowAdminAccessDeniedWhenCallerIsNotAdmin() {
        givenCollab(collab(ColabStatus.OPEN, null));
        givenRequester(CollabMemberRole.MEMBER, CollabMemberStatus.ACCEPTED);

        assertThatThrownBy(() -> useCase.update(command("text")))
                .isInstanceOf(CollabAdminAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        assertNoSideEffects();
    }

    @ParameterizedTest
    @EnumSource(value = CollabMemberStatus.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void shouldThrowAdminAccessDeniedWhenAdminMembershipIsNotAccepted(CollabMemberStatus status) {
        givenCollab(collab(ColabStatus.OPEN, null));
        givenRequester(CollabMemberRole.ADMIN, status);

        assertThatThrownBy(() -> useCase.update(command("text")))
                .isInstanceOf(CollabAdminAccessDeniedException.class);

        assertNoSideEffects();
    }

    @Test
    void shouldThrowAdminAccessDeniedWhenCallerHasNoMembership() {
        givenCollab(collab(ColabStatus.OPEN, null));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(command("text")))
                .isInstanceOf(CollabAdminAccessDeniedException.class);

        assertNoSideEffects();
    }

    @Test
    void shouldThrowCollabNotFoundWhenCollabDoesNotExist() {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(command("text")))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());

        verify(collabMemberRepository, never()).findByCollabIdAndUserId(any(UUID.class), any(UUID.class));
        assertNoSideEffects();
    }

    @Test
    void shouldThrowCollabNotFoundWhenCollabIsDeleted() {
        givenCollab(collab(ColabStatus.DELETED, null));

        assertThatThrownBy(() -> useCase.update(command("text")))
                .isInstanceOf(CollabNotFoundException.class);

        assertNoSideEffects();
    }

    @Test
    void shouldRejectACommandWithoutIdentifiers() {
        assertThatThrownBy(() -> useCase.update(new UpdateCollabJoinInstructionsCommand(null, ADMIN_ID, "text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.update(new UpdateCollabJoinInstructionsCommand(COLLAB_ID, null, "text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.update(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(collabRepository, never()).findById(any(UUID.class));
    }

    private void assertNoSideEffects() {
        verify(collabRepository, never()).save(any(Collab.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    private void givenCollab(Collab collab) {
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
    }

    private void givenRequester(CollabMemberRole role, CollabMemberStatus status) {
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, ADMIN_ID)).thenReturn(Optional.of(
                new CollabMember(COLLAB_ID, new UserId(ADMIN_ID), status, role, Instant.now())
        ));
    }

    private UpdateCollabJoinInstructionsCommand command(String joinInstructions) {
        return new UpdateCollabJoinInstructionsCommand(COLLAB_ID, ADMIN_ID, joinInstructions);
    }

    private Collab collab(ColabStatus status, CollabJoinInstructions joinInstructions) {
        return new Collab(COLLAB_ID, new CollabTitle("Collab"), new UserId(ADMIN_ID), status, Instant.now(),
                joinInstructions);
    }

    private CollabJoinInstructionsUpdatedEvent event(String joinInstructions) {
        return CollabJoinInstructionsUpdatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .collabId(COLLAB_ID)
                .joinInstructions(joinInstructions)
                .build();
    }
}
