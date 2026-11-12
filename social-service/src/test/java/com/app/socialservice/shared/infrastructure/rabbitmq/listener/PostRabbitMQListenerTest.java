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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostRabbitMQListenerTest {

    @Mock
    private UserStatsService userStatsService;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Spy
    private RabbitMQProperties rabbitMQProperties = new RabbitMQProperties();

    @InjectMocks
    private PostRabbitMQListener postRabbitMQListener;

    @BeforeEach
    void setUp() {
        rabbitMQProperties.getQueue().getPost().setCreated("q.social-service.post.created");
        rabbitMQProperties.getQueue().getPost().setDeleted("q.social-service.post.deleted");
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void shouldRecordThePostCreatedEventThenCountItInTheSameTransaction() {
        var event = postCreatedEvent();
        stubInserted(event.id(), event.correlationId(), PostCreatedEvent.class.getSimpleName(), 1);

        postRabbitMQListener.onPostCreated(event);

        InOrder inOrder = inOrder(transactionManager, processedEventsRepository, userStatsService);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(processedEventsRepository).insertIfAbsent(
                event.id(), TargetDatabase.POSTGRES.name(), event.correlationId(), PostCreatedEvent.class.getSimpleName());
        inOrder.verify(userStatsService).incrementPostCount(event.userId());
        inOrder.verify(transactionManager).commit(any());
    }

    @Test
    void shouldRecordThePostDeletedEventThenCountItInTheSameTransaction() {
        var userId = UUID.randomUUID();
        var event = PostDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();
        stubInserted(event.id(), event.correlationId(), PostDeletedEvent.class.getSimpleName(), 1);

        postRabbitMQListener.onPostDeleted(event);

        verify(userStatsService).decrementPostCount(userId);
        verify(transactionManager).commit(any());
    }

    @Test
    void shouldNotCountAnEventAnotherDeliveryAlreadyRecorded() {
        // Also a concurrent redelivery: the insert of the processed event finds the row
        var event = postCreatedEvent();
        stubInserted(event.id(), event.correlationId(), PostCreatedEvent.class.getSimpleName(), 0);

        postRabbitMQListener.onPostCreated(event);

        verify(userStatsService, never()).incrementPostCount(any());
    }

    @Test
    void shouldRollBackTheProcessedEventWhenTheCounterUpdateFails() {
        var event = postCreatedEvent();
        stubInserted(event.id(), event.correlationId(), PostCreatedEvent.class.getSimpleName(), 1);
        doThrow(new RuntimeException("counter update failed"))
                .when(userStatsService)
                .incrementPostCount(event.userId());

        assertThatThrownBy(() -> postRabbitMQListener.onPostCreated(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("counter update failed");

        // The redelivery finds no processed event and counts it then
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
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

    private void stubInserted(UUID eventId, UUID correlationId, String eventType, int rows) {
        when(processedEventsRepository.insertIfAbsent(eventId, TargetDatabase.POSTGRES.name(), correlationId, eventType))
                .thenReturn(rows);
    }

    private PostCreatedEvent postCreatedEvent() {
        return PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .build();
    }
}
