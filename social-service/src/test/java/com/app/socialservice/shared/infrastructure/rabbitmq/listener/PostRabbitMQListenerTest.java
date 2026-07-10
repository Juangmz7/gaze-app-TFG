package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostRabbitMQListenerTest {

    @Mock
    private UserStatsService userStatsService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private PostRabbitMQListener postRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getPost().setCreated("q.social-service.post.created");
        rabbitMQProperties.getQueue().getPost().setDeleted("q.social-service.post.deleted");
    }

    @Test
    void shouldDelegatePostCreatedEventToUserStatsServiceAndRecordProcessedEvent() {
        var userId = UUID.randomUUID();
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);

        postRabbitMQListener.onPostCreated(event);

        verify(userStatsService).incrementPostCount(userId);
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                PostCreatedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldDelegatePostDeletedEventToUserStatsServiceAndRecordProcessedEvent() {
        var userId = UUID.randomUUID();
        var event = PostDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);

        postRabbitMQListener.onPostDeleted(event);

        verify(userStatsService).decrementPostCount(userId);
        verify(processedEventsRepository).insertIfAbsent(
                event.id(),
                TargetDatabase.POSTGRES.name(),
                event.correlationId(),
                PostDeletedEvent.class.getSimpleName()
        );
    }

    @Test
    void shouldRejectInvalidPostCreatedEventPayload() {
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(null)
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> postRabbitMQListener.onPostCreated(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event.occurredAt must not be null");

        verify(userStatsService, never()).incrementPostCount(any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void shouldSkipPostCreatedEventWhenAlreadyProcessed() {
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(true);

        postRabbitMQListener.onPostCreated(event);

        verify(userStatsService, never()).incrementPostCount(any());
        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    void shouldRethrowWhenPostCreatedCounterUpdateFails() {
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();
        when(processedEventsRepository.existsByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).thenReturn(false);
        when(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(
                event.correlationId(),
                TargetDatabase.POSTGRES
        )).thenReturn(false);

        doThrow(new RuntimeException("redis increment failed"))
                .when(userStatsService)
                .incrementPostCount(event.userId());

        assertThatThrownBy(() -> postRabbitMQListener.onPostCreated(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis increment failed");

        verify(processedEventsRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }
}
