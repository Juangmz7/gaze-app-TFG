package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.DeleteUserCommand;
import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.application.commands.UpdateAuthUserInfoCommand;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import com.app.socialservice.user.infrastructure.mapper.UserRegisterCommandMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final UserService userService;
    private final UserRegisterCommandMapper userRegisterCommandMapper;
    private final UserNodeService userNodeService;

    public UserRabbitMQListener(
            UserService userService,
            UserRegisterCommandMapper userRegisterCommandMapper,
            UserNodeService userNodeService,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.userService = userService;
        this.userRegisterCommandMapper = userRegisterCommandMapper;
        this.userNodeService = userNodeService;
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.register}")
    public void onUserRegisteredFromAuth(UserRegisteredFromAuthEvent event) {
        validateUserRegisteredFromAuthEvent(event);

        var eventId = buildDeterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var correlationId = buildDeterministicUuid(
                "auth-register-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var eventType = event.getClass().getSimpleName();

        log.info("UserRegistered event: {} with correlationId: {} received from {}",
                eventId, correlationId, rabbitMQProperties.getQueue().getAuth().getRegister());

        if (isEventAlreadyProcessed(eventId, correlationId)) {
            log.warn("Detected auth register event {} with correlationId {} duplication, discarding message...",
                    eventId, correlationId);
            return;
        }

        var command = userRegisterCommandMapper.toCommand(eventId, correlationId, event, eventType);
        try {
            userService.registerUser(command);
            setEventAsProcessed(eventId, correlationId, eventType);
        } catch (Exception exception) {
            log.warn("Error processing user registration message, correlationId={}", correlationId, exception);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.update}")
    public void onUserInfoFromAuthUpdated(UserInfoFromAuthUpdatedEvent event) {
        validateUserInfoFromAuthUpdatedEvent(event);

        var eventId = buildDeterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );
        var correlationId = buildDeterministicUuid(
                "auth-update-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var eventType = event.getClass().getSimpleName();

        log.info("UserInfoFromAuthUpdated event: {} with correlationId: {} received from {}",
                eventId, correlationId, rabbitMQProperties.getQueue().getAuth().getUpdate());

        var command = new UpdateAuthUserInfoCommand(
                eventId,
                correlationId,
                parseUuid(event.userId(), "event.userId"),
                event.details().username(),
                event.details().email(),
                event.time() != null ? java.time.Instant.ofEpochMilli(event.time()) : null,
                eventType
        );

        if (isEventAlreadyProcessed(command.id(), command.correlationId())) {
            log.warn("Detected auth update event {} with correlationId {} duplication, discarding message...",
                    command.id(), command.correlationId());
            return;
        }

        try {
            userService.updateUserAuthInfo(command);
            setEventAsProcessed(command.id(), command.correlationId(), eventType);
        } catch (Exception exception) {
            log.warn("Error processing user auth info update message, correlationId={}",
                    command.correlationId(), exception);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.delete}")
    public void onUserDeletedFromAuth(UserDeletedFromAuthEvent event) {
        validateUserDeletedFromAuthEvent(event);

        var eventId = buildDeterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var correlationId = buildDeterministicUuid(
                "auth-delete-correlation",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );
        var eventType = event.getClass().getSimpleName();

        log.info("UserDeletedFromAuth event: {} with correlationId: {} received from {}",
                eventId, correlationId, rabbitMQProperties.getQueue().getAuth().getDelete());

        var command = new DeleteUserCommand(
                eventId,
                correlationId,
                parseUuid(event.userId(), "event.userId"),
                event.time() != null ? java.time.Instant.ofEpochMilli(event.time()) : null,
                eventType
        );

        if (isEventAlreadyProcessed(command.id(), command.correlationId())) {
            log.warn("Detected auth delete event {} with correlationId {} duplication, discarding message...",
                    command.id(), command.correlationId());
            return;
        }

        try {
            userService.deleteUser(command);
            setEventAsProcessed(command.id(), command.correlationId(), eventType);
        } catch (Exception exception) {
            log.warn("Error processing user deletion from auth message, correlationId={}",
                    command.correlationId(), exception);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.register}")
    public void syncSecondaryDatabase(UserRegisteredEvent event) {
        validateUserRegisteredEvent(event);
        log.info("UserRegistered event: {} with correlationId: {} received from {}",
                event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getRegister());

        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected user registered event {} with correlationId {} duplication, discarding message...",
                    event.id(), event.correlationId());
            return;
        }

        var command = new SynchroniseSecondaryDatabaseCommand(
                event.correlationId(),
                event.id(),
                event.userId()
        );

        try {
            userNodeService.registerUserNode(command);
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (Exception exception) {
            log.warn("Error processing user registration event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), exception);
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.deleted}")
    public void onUserDeleted(UserDeletedEvent event) {
        validateUserDeletedEvent(event);
        log.info("UserDeleted event: {} with correlationId: {} received from {}",
                event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getDeleted());

        if (isEventAlreadyProcessed(event.id(), event.correlationId())) {
            log.warn("Detected user deleted event {} with correlationId {} duplication, discarding message...",
                    event.id(), event.correlationId());
            return;
        }

        var command = new SynchroniseSecondaryDatabaseCommand(
                event.correlationId(),
                event.id(),
                event.userId()
        );

        try {
            userNodeService.deleteUserNode(command);
            setEventAsProcessed(event.id(), event.correlationId(), event.getClass().getSimpleName());
        } catch (Exception exception) {
            log.warn("Error processing user deletion event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), exception);
        }
    }
}
