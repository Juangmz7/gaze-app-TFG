package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.follow.application.service.FollowNodeService;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FollowRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final FollowNodeService followNodeService;

    public FollowRabbitMQListener(
            FollowNodeService followNodeService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.followNodeService = followNodeService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.follow.created}")
    public void onUserFollowed(UserFollowedEvent event) {
        validateUserFollowedEvent(event);
        log.info("UserFollowed event: {} with correlationId: {} received from {}",
                event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getFollow().getCreated());

        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected follow event {} with correlationId {} duplication, discarding message...",
                    event.id(), event.correlationId());
            return;
        }

        try {
            followNodeService.createFollowRelationship(
                    event.followerUserId(),
                    event.followedUserId()
            );
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (Exception exception) {
            log.warn("Error processing user followed event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }
}
