package com.app.postcommandservice.share.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchCreatePostShareCommandUseCaseTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<CreatePostShareCommand> commandCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<OutboxEventCreatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private DispatchCreatePostShareCommandUseCase dispatchCreatePostShareCommandUseCase;

    @Test
    void shouldSavePendingCreatePostShareCommandOutboxRowAndPublishLocalDomainEvent() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(jsonMapper.toJson(commandCaptor.capture())).thenReturn("serialized-share-command");

        dispatchCreatePostShareCommandUseCase.dispatch(postId, userId);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());

        var command = commandCaptor.getValue();
        var outboxEvent = outboxEventCaptor.getValue();
        var domainEvent = domainEventCaptor.getValue();

        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();

        assertThat(outboxEvent.getId()).isNotNull();
        assertThat(outboxEvent.getCorrelationId()).isEqualTo(command.correlationId());
        assertThat(outboxEvent.getPayload()).isEqualTo("serialized-share-command");
        assertThat(outboxEvent.getEventType()).isEqualTo(CreatePostShareCommand.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);

        assertThat(domainEvent.id()).isEqualTo(outboxEvent.getId());
    }
}
