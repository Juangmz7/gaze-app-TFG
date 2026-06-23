package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BlockRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final BlockNodeService blockNodeService;

    public BlockRabbitMQListener(
            BlockNodeService blockNodeService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.blockNodeService = blockNodeService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.block.created}")
    public void onUserBlocked(UserBlockedEvent event) {
        validateUserBlockedEvent(event);
        log.info("UserBlocked event: {} with correlationId: {} received from {}",
                event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getBlock().getCreated());

        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected block event {} with correlationId {} duplication, discarding message...",
                    event.id(), event.correlationId());
            return;
        }

        try {
            blockNodeService.deleteBidirectionalFollowRelationship(
                    event.blockerUserId(),
                    event.blockedUserId()
            );
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (Exception exception) {
            log.warn("Error processing user blocked event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }
}
