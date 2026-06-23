package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.util.UUID;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import com.app.socialservice.user.infrastructure.mapper.UserRegisterCommandMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class RabbitMQListener {

    private final UserService userService;
    private final UserRegisterCommandMapper userRegisterCommandMapper;
    private final UserNodeService userNodeService;
    private final BlockNodeService blockNodeService;
    private final ProcessedEventsRepository processedEventsRepository;

    @RabbitListener(queues = "${rabbitmq.queue.auth.register}")
    public void onUserRegisteredFromAuth(UserRegisteredFromAuthEvent event) {
        log.info("UserRegistered event received from q.social-service.auth.register");

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String eventType = event.getClass().getSimpleName();

        UserRegisterCommand command =
                userRegisterCommandMapper.toCommand(
                        eventId,
                        correlationId,
                        event,
                        eventType
                );
        try {
            userService.registerUser(command);
        } catch (Exception e) {
            log.warn("Error processing user registration message, correlationId={}",
                    command, e);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.update}")
    public void onUserInfoFromAuthUpdated(UserInfoFromAuthUpdatedEvent event) {
        log.info("UserInfoFromAuthUpdated event received from q.social-service.auth.update");

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String eventType = event.getClass().getSimpleName();

        var command = new UpdateAuthUserInfoCommand(
                eventId,
                correlationId,
                UUID.fromString(event.userId()),
                event.details().username(),
                event.details().email(),
                event.time() != null ? java.time.Instant.ofEpochMilli(event.time()) : null,
                eventType
        );

        try {
            userService.updateUserAuthInfo(command);
        } catch (Exception e) {
            log.warn("Error processing user auth info update message, correlationId={}",
                    command.correlationId(), e);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.delete}")
    public void onUserDeletedFromAuth(UserDeletedFromAuthEvent event) {
        log.info("UserDeletedFromAuth event received from q.social-service.auth.delete");

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String eventType = event.getClass().getSimpleName();

        var command = new DeleteUserCommand(
                eventId,
                correlationId,
                UUID.fromString(event.userId()),
                event.time() != null ? java.time.Instant.ofEpochMilli(event.time()) : null,
                eventType
        );

        try {
            userService.deleteUser(command);
        } catch (Exception e) {
            log.warn("Error processing user deletion from auth message, correlationId={}",
                    command.correlationId(), e);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.register}")
    public void syncSecondaryDatabase(UserRegisteredEvent event) {
        log.info("UserRegistered event: {} with correlationId: {} received from q.social-service.user.register",
                event.id(), event.correlationId());

        var command = new SynchroniseSecondaryDatabaseCommand(
                event.correlationId(),
                event.id(),
                event.userId()
        );

        try {
            userNodeService.registerUserNode(command);
        } catch (Exception e) {
            log.warn("Error processing user registration event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), e);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.deleted}")
    public void onUserDeleted(UserDeletedEvent event) {
        log.info("UserDeleted event: {} with correlationId: {} received from q.social-service.user.deleted",
                event.id(), event.correlationId());

        var command = new SynchroniseSecondaryDatabaseCommand(
                event.correlationId(),
                event.id(),
                event.userId()
        );

        try {
            userNodeService.deleteUserNode(command);
        } catch (Exception e) {
            log.warn("Error processing user deletion event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), e);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.block.created}")
    public void onUserBlocked(UserBlockedEvent event) {
        validateUserBlockedEvent(event);
        log.info("UserBlocked event: {} with correlationId: {} received from {}",
                event.id(), event.correlationId(), "q.social-service.user.block.created");

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
            setEventAsProcessed(event);
        } catch (Exception e) {
            log.warn("Error processing user blocked event: {} with correlationId={}",
                    event.id(), event.correlationId(), e);
            throw e;
        }
    }

    private boolean isEventAlreadyProcessed(UUID eventId, UUID correlationId) {
        return processedEventsRepository.existsById(eventId)
                || processedEventsRepository.existsByCorrelationId(correlationId);
    }

    private void setEventAsProcessed(UserBlockedEvent event) {
        processedEventsRepository.save(new ProcessedEvent(
                event.id(),
                event.correlationId(),
                event.getClass().getSimpleName()
        ));
    }

    private void validateUserBlockedEvent(UserBlockedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.blockerUserId() == null) {
            throw new IllegalArgumentException("event.blockerUserId must not be null");
        }
        if (event.blockedUserId() == null) {
            throw new IllegalArgumentException("event.blockedUserId must not be null");
        }
    }
}
