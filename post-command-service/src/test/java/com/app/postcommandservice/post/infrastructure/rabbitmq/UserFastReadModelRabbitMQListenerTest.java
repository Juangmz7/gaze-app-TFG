package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.events.UserBioEventPayload;
import com.app.postcommandservice.post.infrastructure.events.UserBlockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserRegisteredEvent;
import com.app.postcommandservice.post.infrastructure.events.UserFollowedEvent;
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
class UserFastReadModelRabbitMQListenerTest {

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

    private UserFastReadModelRabbitMQListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserFastReadModelRabbitMQListener(
                userReadModelJpaRepository,
                blockReadModelJpaRepository,
                followReadModelJpaRepository,
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
                "alice",
                "alice@email.com",
                new UserBioEventPayload("Alice's description", Map.of("Tiktok", "Alice_09"))
        );
        var blockedEvent = new UserBlockedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(1),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        var followedEvent = new UserFollowedEvent(
                UUID.randomUUID(),
                correlationId,
                occurredAt.plusSeconds(2),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        when(processedEventsRepository.existsById(registeredEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(blockedEvent.id())).thenReturn(false);
        when(processedEventsRepository.existsById(followedEvent.id())).thenReturn(false);

        listener.onUserRegistered(registeredEvent);
        listener.onUserBlocked(blockedEvent);
        listener.onUserFollowed(followedEvent);

        verify(userReadModelJpaRepository).save(any());
        verify(blockReadModelJpaRepository).save(any());
        verify(followReadModelJpaRepository).save(any());
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
        verify(processedEventsRepository).insertIfAbsent(
                followedEvent.id(),
                correlationId,
                UserFollowedEvent.class.getSimpleName()
        );
        verify(processedEventsRepository, times(3)).existsById(any(UUID.class));
    }
}
