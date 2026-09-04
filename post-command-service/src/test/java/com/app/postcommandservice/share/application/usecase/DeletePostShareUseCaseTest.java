package com.app.postcommandservice.share.application.usecase;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.share.application.commands.DeletePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.domain.events.PostShareDeletedDomainEvent;
import com.app.postcommandservice.share.infrastructure.events.PostShareDeletedEvent;
import com.app.postcommandservice.share.infrastructure.mapper.PostShareEventMapper;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletePostShareUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PostShareRepository postShareRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostShareEventMapper postShareEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private DeletePostShareUseCase deletePostShareUseCase;

    @Test
    void shouldDeleteShareAndPublishPostShareDeletedEventWhenShareExists() {
        var command = new DeletePostShareCommand(POST_ID, USER_ID);
        var event = PostShareDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .userId(USER_ID)
                .build();

        when(postShareRepository.deleteByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(1);
        when(postShareEventMapper.toPostShareDeletedEvent(
                any(UUID.class),
                any(UUID.class),
                eq(POST_ID),
                eq(USER_ID),
                any(Instant.class)
        )).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        deletePostShareUseCase.delete(command);

        verify(postShareRepository).deleteByPostIdAndUserId(POST_ID, USER_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostShareDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isNotNull();
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{\"payload\":true}");
        verify(applicationEventPublisher).publishEvent(any(PostShareDeletedDomainEvent.class));
    }

    @Test
    void shouldReturnGracefullyWithoutEventWhenShareDoesNotExist() {
        when(postShareRepository.deleteByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(0);

        deletePostShareUseCase.delete(new DeletePostShareCommand(POST_ID, USER_ID));

        verify(postShareRepository).deleteByPostIdAndUserId(POST_ID, USER_ID);
        verify(postShareEventMapper, never()).toPostShareDeletedEvent(
                any(UUID.class),
                any(UUID.class),
                any(UUID.class),
                any(UUID.class),
                any(Instant.class)
        );
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostShareDeletedDomainEvent.class));
    }

    @Test
    void shouldUseCompositeKeyDeleteSoAnotherUsersShareIsNotRemoved() {
        var otherUserId = UUID.randomUUID();
        when(postShareRepository.deleteByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(0);

        deletePostShareUseCase.delete(new DeletePostShareCommand(POST_ID, USER_ID));

        verify(postShareRepository).deleteByPostIdAndUserId(POST_ID, USER_ID);
        verify(postShareRepository, never()).deleteByPostIdAndUserId(POST_ID, otherUserId);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }
}
