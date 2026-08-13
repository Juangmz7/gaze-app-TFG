package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.events.UserBlockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserRegisteredEvent;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserFastReadModelRabbitMQListenerTest {

    @Mock
    private UserReadModelJpaRepository userReadModelJpaRepository;

    @Mock
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    private UserFastReadModelRabbitMQListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserFastReadModelRabbitMQListener(
                userReadModelJpaRepository,
                blockReadModelJpaRepository,
                processedEventsRepository,
                rabbitMQProperties
        );
    }

    @Test
    void shouldProcessDistinctFastEventsThatShareTheSameCorrelationId() {
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now();
        var registeredEvent = new UserRegisteredEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt,
                UUID.randomUUID(),
                "alice"
        );
        var blockedEvent = new UserBlockedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(1),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(processedEventsRepository.existsById(registeredEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(blockedEvent.id())).thenReturn(false);

        listener.onUserRegistered(registeredEvent);
        listener.onUserBlocked(blockedEvent);

        verify(processedEventsRepository, never()).existsByCorrelationId(any());
        verify(userReadModelJpaRepository).save(any());
        verify(blockReadModelJpaRepository).save(any());
        verify(processedEventsRepository).insertIfAbsent(
                registeredEvent.id(),
                correlationId,
                UserRegisteredEvent.class.getSimpleName()
        );
        verify(processedEventsRepository).insertIfAbsent(
                blockedEvent.id(),
                correlationId,
                UserBlockedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository, times(2)).existsById(any(UUID.class));
    }
}
