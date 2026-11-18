package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.util.UUID;
import java.util.function.Consumer;

import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.domain.exception.DomainException;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each user's post count. The processed event and the counter change commit in
 * one transaction, and the counter only moves when this delivery inserted the processed
 * event: a redelivery, also a concurrent one, finds it and counts nothing.
 */
@Slf4j
@Component
public class PostRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final UserStatsService userStatsService;
    private final TransactionTemplate transactionTemplate;

    public PostRabbitMQListener(
            UserStatsService userStatsService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties,
            PlatformTransactionManager transactionManager) {
        super(processedEventsRepository, rabbitMQProperties);
        this.userStatsService = userStatsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @RabbitListener(queues = "${rabbitmq.queue.post.created}")
    public void onPostCreated(PostCreatedEvent event) {
        try {
            validatePostCreatedEvent(event);
            log.info("PostCreated event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getPost().getCreated());

            var counted = countOnce(event.id(), event.correlationId(), event.getClass().getSimpleName(),
                    event.userId(), userStatsService::incrementPostCount);
            if (!counted) {
                log.warn("Detected post-created event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
            }
        } catch (IllegalArgumentException exception) {
            log.error("Invalid post-created event", exception);
            throw exception;
        } catch (DomainException exception) {
            log.error("Domain error processing post-created event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing post-created event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.post.deleted}")
    public void onPostDeleted(PostDeletedEvent event) {
        try {
            validatePostDeletedEvent(event);
            log.info("PostDeleted event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getPost().getDeleted());

            var counted = countOnce(event.id(), event.correlationId(), event.getClass().getSimpleName(),
                    event.userId(), userStatsService::decrementPostCount);
            if (!counted) {
                log.warn("Detected post-deleted event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
            }
        } catch (IllegalArgumentException exception) {
            log.error("Invalid post-deleted event", exception);
            throw exception;
        } catch (DomainException exception) {
            log.error("Domain error processing post-deleted event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing post-deleted event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }

    /** Records the event and changes the counter in one transaction; false for a duplicate. */
    private boolean countOnce(UUID eventId, UUID correlationId, String eventType, UUID userId, Consumer<UUID> change) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!markEventAsProcessed(eventId, correlationId, eventType, TargetDatabase.POSTGRES)) {
                return false;
            }
            change.accept(userId);
            return true;
        }));
    }
}
