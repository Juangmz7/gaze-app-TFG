package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.UserReadModelEntity;
import com.app.postcommandservice.post.infrastructure.events.UserBlockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserRegisteredEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUpdatedEvent;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.listener.AbstractRabbitMQListenerSupport;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

@Slf4j
@Component
@RabbitListener(queues = "${rabbitmq.queue.user.fast}")
public class UserFastReadModelRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final UserReadModelJpaRepository userReadModelJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    public UserFastReadModelRabbitMQListener(
            UserReadModelJpaRepository userReadModelJpaRepository,
            BlockReadModelJpaRepository blockReadModelJpaRepository,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.userReadModelJpaRepository = userReadModelJpaRepository;
        this.blockReadModelJpaRepository = blockReadModelJpaRepository;
    }

    @Transactional
    @RabbitHandler
    public void onUserRegistered(UserRegisteredEvent event) {
        validateUserRegisteredEvent(event);
        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected duplicate user registered event {}, skipping", event.id());
            return;
        }

        userReadModelJpaRepository.save(new UserReadModelEntity(
                event.userId(),
                event.username(),
                event.occurredAt(),
                event.occurredAt()
        ));
        setEventAsProcessed(event.id(), event.correlationId(), UserRegisteredEvent.class.getSimpleName());
    }

    @Transactional
    @RabbitHandler
    public void onUserUpdated(UserUpdatedEvent event) {
        validateUserUpdatedEvent(event);
        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected duplicate user updated event {}, skipping", event.id());
            return;
        }

        var entity = userReadModelJpaRepository.findById(event.userId())
                .orElse(new UserReadModelEntity(
                        event.userId(),
                        event.username(),
                        event.createdAt() == null ? event.occurredAt() : event.createdAt(),
                        event.updatedAt() == null ? event.occurredAt() : event.updatedAt()
                ));
        entity.setUsername(event.username());
        entity.setUpdatedAt(event.updatedAt() == null ? event.occurredAt() : event.updatedAt());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(event.createdAt() == null ? event.occurredAt() : event.createdAt());
        }

        userReadModelJpaRepository.save(entity);
        setEventAsProcessed(event.id(), event.correlationId(), UserUpdatedEvent.class.getSimpleName());
    }

    @Transactional
    @RabbitHandler
    public void onUserBlocked(UserBlockedEvent event) {
        validateUserBlockedEvent(
                event.id(),
                event.correlationId(),
                event.occurredAt(),
                event.blockerUserId(),
                event.blockedUserId()
        );
        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected duplicate user blocked event {}, skipping", event.id());
            return;
        }

        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(event.blockerUserId(), event.blockedUserId()),
                event.occurredAt()
        ));
        setEventAsProcessed(event.id(), event.correlationId(), UserBlockedEvent.class.getSimpleName());
    }

    @RabbitHandler(isDefault = true)
    public void onUnsupportedFastEvent(Object ignored) {
        throw rejectToDlq(new IllegalArgumentException("Unsupported fast user event payload"));
    }
}
