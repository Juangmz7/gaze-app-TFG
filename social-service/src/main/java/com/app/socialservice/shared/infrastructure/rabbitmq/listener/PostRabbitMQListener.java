package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.domain.exception.DomainException;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PostRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final UserStatsService userStatsService;

    public PostRabbitMQListener(
            UserStatsService userStatsService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.userStatsService = userStatsService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.post.created}")
    public void onPostCreated(PostCreatedEvent event) {
        try {
            validatePostCreatedEvent(event);
            log.info("PostCreated event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getPost().getCreated());

            if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
                log.warn("Detected post-created event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            userStatsService.incrementPostCount(event.userId());
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
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

            if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
                log.warn("Detected post-deleted event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            userStatsService.decrementPostCount(event.userId());
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
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
}
