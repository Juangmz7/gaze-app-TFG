package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.events.UserDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnblockedEvent;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSlowReadModelRabbitMQListenerTest {

    @Mock
    private UserReadModelJpaRepository userReadModelJpaRepository;

    @Mock
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    private UserSlowReadModelRabbitMQListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserSlowReadModelRabbitMQListener(
                userReadModelJpaRepository,
                blockReadModelJpaRepository,
                processedEventsRepository,
                rabbitMQProperties
        );
    }

    @Test
    void shouldProcessDistinctSlowEventsThatShareTheSameCorrelationId() {
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now();
        var userDeletedEvent = new UserDeletedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt,
                UUID.randomUUID()
        );
        var userUnblockedEvent = new UserUnblockedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(1),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(processedEventsRepository.existsById(userDeletedEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(userUnblockedEvent.id())).thenReturn(false);

        listener.onUserDeleted(userDeletedEvent);
        listener.onUserUnblocked(userUnblockedEvent);

        verify(processedEventsRepository, never()).existsByCorrelationId(any());
        verify(userReadModelJpaRepository).deleteById(userDeletedEvent.userId());
        verify(blockReadModelJpaRepository).deleteById(
                new BlockReadModelId(userUnblockedEvent.blockerUserId(), userUnblockedEvent.blockedUserId())
        );
        verify(processedEventsRepository).insertIfAbsent(
                userDeletedEvent.id(),
                correlationId,
                UserDeletedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository).insertIfAbsent(
                userUnblockedEvent.id(),
                correlationId,
                UserUnblockedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository, times(2)).existsById(any(UUID.class));
    }
}
