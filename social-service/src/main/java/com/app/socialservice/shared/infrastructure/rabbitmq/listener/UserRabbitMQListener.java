package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.shared.domain.exception.DomainException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
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
import com.app.socialservice.user.domain.exception.InvalidEmailException;
import com.app.socialservice.user.domain.exception.InvalidUserIdException;
import com.app.socialservice.user.domain.exception.InvalidUsernameException;
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
        try {
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

            if (isEventAlreadyProcessed(eventId, correlationId, TargetDatabase.POSTGRES)) {
                log.warn("Detected auth register event {} with correlationId {} duplication, discarding message...",
                        eventId, correlationId);
                return;
            }

            var command = userRegisterCommandMapper.toCommand(eventId, correlationId, event, eventType);
            userService.registerUser(command);
            setEventAsProcessed(eventId, correlationId, eventType, TargetDatabase.POSTGRES);
        } catch (IllegalArgumentException exception) {
            log.error("Invalid auth register event", exception);
            throw exception;
        } catch (InvalidEmailException | InvalidUserIdException | InvalidUsernameException | UserNotFoundException exception) {
            log.warn("Non-retryable user business error processing auth register event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing auth register event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user registration message", exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.update}")
    public void onUserInfoFromAuthUpdated(UserInfoFromAuthUpdatedEvent event) {
        try {
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
                    java.time.Instant.ofEpochMilli(event.time()),
                    eventType
            );

            if (isEventAlreadyProcessed(command.id(), command.correlationId(), TargetDatabase.POSTGRES)) {
                log.warn("Detected auth update event {} with correlationId {} duplication, discarding message...",
                        command.id(), command.correlationId());
                return;
            }

            userService.updateUserAuthInfo(command);
            setEventAsProcessed(command.id(), command.correlationId(), eventType, TargetDatabase.POSTGRES);
        } catch (IllegalArgumentException exception) {
            log.error("Invalid auth update event", exception);
            throw exception;
        } catch (InvalidEmailException | InvalidUserIdException | InvalidUsernameException | UserNotFoundException exception) {
            log.warn("Non-retryable user business error processing auth update event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing auth update event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user auth info update message", exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.auth.delete}")
    public void onUserDeletedFromAuth(UserDeletedFromAuthEvent event) {
        try {
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
                    java.time.Instant.ofEpochMilli(event.time()),
                    eventType
            );

            if (isEventAlreadyProcessed(command.id(), command.correlationId(), TargetDatabase.POSTGRES)) {
                log.warn("Detected auth delete event {} with correlationId {} duplication, discarding message...",
                        command.id(), command.correlationId());
                return;
            }

            userService.deleteUser(command);
            setEventAsProcessed(command.id(), command.correlationId(), eventType, TargetDatabase.POSTGRES);
        } catch (IllegalArgumentException exception) {
            log.error("Invalid auth delete event", exception);
            throw exception;
        } catch (InvalidEmailException | InvalidUserIdException | InvalidUsernameException | UserNotFoundException exception) {
            log.warn("Non-retryable user business error processing auth delete event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing auth delete event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user deletion from auth message", exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.register}")
    public void syncSecondaryDatabase(UserRegisteredEvent event) {
        try {
            validateUserRegisteredEvent(event);
            log.info("UserRegistered event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getRegister());

            if (isEventAlreadyProcessed(event.id(), event.correlationId(), TargetDatabase.NEO4J)) {
                log.warn("Detected user registered event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            var command = new SynchroniseSecondaryDatabaseCommand(
                    event.correlationId(),
                    event.id(),
                    event.userId()
            );

            userNodeService.registerUserNode(command);
            setEventAsProcessed(
                    event.id(),
                    event.correlationId(),
                    event.getClass().getSimpleName(),
                    TargetDatabase.NEO4J
            );
        } catch (IllegalArgumentException exception) {
            log.error("Invalid user registered event", exception);
            throw exception;
        } catch (InvalidEmailException | InvalidUserIdException | InvalidUsernameException | UserNotFoundException exception) {
            log.warn("Non-retryable user business error processing user registered event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing user registered event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user registration event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.user.deleted}")
    public void onUserDeleted(UserDeletedEvent event) {
        try {
            validateUserDeletedEvent(event);
            log.info("UserDeleted event: {} with correlationId: {} received from {}",
                    event.id(), event.correlationId(), rabbitMQProperties.getQueue().getUser().getDeleted());

            if (isEventAlreadyProcessed(event.id(), event.correlationId(), TargetDatabase.NEO4J)) {
                log.warn("Detected user deleted event {} with correlationId {} duplication, discarding message...",
                        event.id(), event.correlationId());
                return;
            }

            var command = new SynchroniseSecondaryDatabaseCommand(
                    event.correlationId(),
                    event.id(),
                    event.userId()
            );

            userNodeService.deleteUserNode(command);
            setEventAsProcessed(
                    event.id(),
                    event.correlationId(),
                    event.getClass().getSimpleName(),
                    TargetDatabase.NEO4J
            );
        } catch (IllegalArgumentException exception) {
            log.error("Invalid user deleted event", exception);
            throw exception;
        } catch (InvalidEmailException | InvalidUserIdException | InvalidUsernameException | UserNotFoundException exception) {
            log.warn("Non-retryable user business error processing user deleted event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (DomainException exception) {
            log.warn("Domain error processing user deleted event rejected to DLQ", exception);
            throw rejectToDlq(exception);
        } catch (Exception exception) {
            log.error("Retryable error processing user deletion event: {} with correlationId={}",
                    event.id(), event.correlationId(), exception);
            throw exception;
        }
    }
}
