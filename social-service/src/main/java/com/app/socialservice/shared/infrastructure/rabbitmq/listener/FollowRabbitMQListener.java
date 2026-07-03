package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.follow.application.service.FollowNodeService;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.shared.domain.exception.DomainException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.service.UserStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FollowRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final FollowNodeService followNodeService;
    private final UserStatsService userStatsService;

    public FollowRabbitMQListener(
            FollowNodeService followNodeService,
            UserStatsService userStatsService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.followNodeService = followNodeService;
        this.userStatsService = userStatsService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.follow.created}")
    public void onUserFollowed(UserFollowedEvent event) {
        try {
            validateUserFollowedEvent(event);
            log.info("UserFollowed event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getFollow().getCreated());

            if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
                log.warn("Detected follow event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            followNodeService.createFollowRelationship(
                    event.followerUserId(),
                    event.followedUserId()
            );
            userStatsService.incrementFollowCounters(
                    event.followerUserId(),
                    event.followedUserId()
            );
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (IllegalArgumentException exception) {
            log.error("Invalid user followed event", exception);
            throw exception;
        } catch (FollowBlockedException | SelfFollowNotAllowedException | UserNotFoundException exception) {
            log.warn("Non-retryable follow business error processing user followed event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing user followed event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user followed event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.follow.deleted}")
    public void onUserUnfollowed(UserUnfollowedEvent event) {
        try {
            validateUserUnfollowedEvent(event);
            log.info("UserUnfollowed event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getFollow().getDeleted());

            if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
                log.warn("Detected unfollow event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            followNodeService.deleteFollowRelationship(
                    event.followerUserId(),
                    event.followedUserId()
            );
            userStatsService.decrementFollowCounters(
                    event.followerUserId(),
                    event.followedUserId()
            );
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (IllegalArgumentException exception) {
            log.error("Invalid user unfollowed event", exception);
            throw exception;
        } catch (SelfUnfollowNotAllowedException | UserNotFoundException exception) {
            log.warn("Non-retryable follow business error processing user unfollowed event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing user unfollowed event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user unfollowed event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }
}
