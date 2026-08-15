package com.app.postcommandservice.like.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchValidatePostUnlikeCommandUseCaseTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<ValidatePostUnlikeCommand> commandCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<OutboxEventCreatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;

    @Test
    void shouldSavePendingValidatePostUnlikeCommandOutboxRowAndPublishLocalDomainEvent() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var source = PostLikeSource.USER_PROFILE;
        var feedPosition = 7;
        when(jsonMapper.toJson(commandCaptor.capture())).thenReturn("serialized-unlike-command");

        dispatchValidatePostUnlikeCommandUseCase.dispatch(postId, userId, source, feedPosition);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());

        var command = commandCaptor.getValue();
        var outboxEvent = outboxEventCaptor.getValue();
        var domainEvent = domainEventCaptor.getValue();

        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.source()).isEqualTo(source);
        assertThat(command.feedPosition()).isEqualTo(feedPosition);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();

        assertThat(outboxEvent.getId()).isNotNull();
        assertThat(outboxEvent.getCorrelationId()).isEqualTo(command.correlationId());
        assertThat(outboxEvent.getPayload()).isEqualTo("serialized-unlike-command");
        assertThat(outboxEvent.getEventType()).isEqualTo(ValidatePostUnlikeCommand.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);

        assertThat(domainEvent.id()).isEqualTo(outboxEvent.getId());
    }
}
