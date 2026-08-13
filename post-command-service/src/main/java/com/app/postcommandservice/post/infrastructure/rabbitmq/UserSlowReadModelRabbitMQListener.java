package com.app.postcommandservice.post.infrastructure.rabbitmq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.events.UserDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnblockedEvent;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.listener.AbstractRabbitMQListenerSupport;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

@Slf4j
@Component
@RabbitListener(queues = "${rabbitmq.queue.user.slow}")
public class UserSlowReadModelRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final UserReadModelJpaRepository userReadModelJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    public UserSlowReadModelRabbitMQListener(
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
    public void onUserDeleted(UserDeletedEvent event) {
        validateUserDeletedEvent(event);
        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected duplicate user deleted event {}, skipping", event.id());
            return;
        }

        userReadModelJpaRepository.deleteById(event.userId());
        setEventAsProcessed(event.id(), event.correlationId(), UserDeletedEvent.class.getSimpleName());
    }

    @Transactional
    @RabbitHandler
    public void onUserUnblocked(UserUnblockedEvent event) {
        validateUserBlockedEvent(
                event.id(),
                event.correlationId(),
                event.occurredAt(),
                event.blockerUserId(),
                event.blockedUserId()
        );
        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected duplicate user unblocked event {}, skipping", event.id());
            return;
        }

        blockReadModelJpaRepository.deleteById(new BlockReadModelId(event.blockerUserId(), event.blockedUserId()));
        setEventAsProcessed(event.id(), event.correlationId(), UserUnblockedEvent.class.getSimpleName());
    }

    @RabbitHandler(isDefault = true)
    public void onUnsupportedSlowEvent(Object ignored) {
        throw rejectToDlq(new IllegalArgumentException("Unsupported slow user event payload"));
    }
}
