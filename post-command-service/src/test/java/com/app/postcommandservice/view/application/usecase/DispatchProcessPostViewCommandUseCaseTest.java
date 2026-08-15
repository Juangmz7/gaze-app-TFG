package com.app.postcommandservice.view.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchProcessPostViewCommandUseCaseTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<ProcessPostViewCommand> commandCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<OutboxEventCreatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private DispatchProcessPostViewCommandUseCase dispatchProcessPostViewCommandUseCase;

    @Test
    void shouldSavePendingProcessPostViewCommandOutboxRowAndPublishLocalDomainEvent() {
        var postId = UUID.randomUUID();
        var viewId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(jsonMapper.toJson(commandCaptor.capture())).thenReturn("serialized-view-command");

        dispatchProcessPostViewCommandUseCase.dispatch(
                viewId,
                postId,
                userId,
                PostViewSource.SEARCH,
                4,
                3000,
                2100,
                70,
                PostViewExitReason.VIDEO_COMPLETED
        );

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());

        var command = commandCaptor.getValue();
        var outboxEvent = outboxEventCaptor.getValue();
        var domainEvent = domainEventCaptor.getValue();

        assertThat(command.viewId()).isEqualTo(viewId);
        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.source()).isEqualTo(PostViewSource.SEARCH);
        assertThat(command.feedPosition()).isEqualTo(4);
        assertThat(command.durationMs()).isEqualTo(3000);
        assertThat(command.timeWatchedMs()).isEqualTo(2100);
        assertThat(command.completionPercent()).isEqualTo(70);
        assertThat(command.exitReason()).isEqualTo(PostViewExitReason.VIDEO_COMPLETED);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();

        assertThat(outboxEvent.getId()).isNotNull();
        assertThat(outboxEvent.getCorrelationId()).isEqualTo(command.correlationId());
        assertThat(outboxEvent.getPayload()).isEqualTo("serialized-view-command");
        assertThat(outboxEvent.getEventType()).isEqualTo(ProcessPostViewCommand.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);

        assertThat(domainEvent.id()).isEqualTo(outboxEvent.getId());
    }
}
