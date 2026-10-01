package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.events.UserDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnblockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnfollowedEvent;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.FollowReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private FollowReadModelJpaRepository followReadModelJpaRepository;

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
                followReadModelJpaRepository,
                processedEventsRepository,
                rabbitMQProperties
        );
    }

    @Test
    void shouldProcessDistinctSlowEventsThatShareTheSameCorrelationId() {
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now();
        var deletedEvent = new UserDeletedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt,
                UUID.randomUUID()
        );
        var unblockedEvent = new UserUnblockedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(1),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        var unfollowedEvent = new UserUnfollowedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(2),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(processedEventsRepository.existsById(deletedEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(unblockedEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(unfollowedEvent.id())).thenReturn(false);

        listener.onUserDeleted(deletedEvent);
        listener.onUserUnblocked(unblockedEvent);
        listener.onUserUnfollowed(unfollowedEvent);

        verify(userReadModelJpaRepository).deleteById(any());
        verify(blockReadModelJpaRepository).deleteById(any());
        verify(followReadModelJpaRepository).deleteById(any());
        verify(processedEventsRepository).insertIfAbsent(
                deletedEvent.id(),
                correlationId,
                UserDeletedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository).insertIfAbsent(
                unblockedEvent.id(),
                correlationId,
                UserUnblockedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository).insertIfAbsent(
                unfollowedEvent.id(),
                correlationId,
                UserUnfollowedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository, times(3)).existsById(any(UUID.class));
    }
}
