package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.app.socialservice.block.application.service.BlockNodeService;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
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
import org.springframework.util.StringUtils;

@Slf4j
@RequiredArgsConstructor
@Component
public class RabbitMQListener {

    private final UserService userService;
    private final UserRegisterCommandMapper userRegisterCommandMapper;
    private final UserNodeService userNodeService;
    private final BlockNodeService blockNodeService;
    private final ProcessedEventsRepository processedEventsRepository;
    private final RabbitMQProperties rabbitMQProperties;

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
        } catch (Exception e) {
            log.warn("Error processing user registration message, correlationId={}",
                    correlationId, e);
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
        } catch (Exception e) {
            log.warn("Error processing user auth info update message, correlationId={}",
                    command.correlationId(), e);
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
        } catch (Exception e) {
            log.warn("Error processing user deletion from auth message, correlationId={}",
                    command.correlationId(), e);
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
        } catch (Exception e) {
            log.warn("Error processing user registration event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), e);
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
        } catch (Exception e) {
            log.warn("Error processing user deletion event: {} with correlationId={}",
                    command.eventId(), command.correlationId(), e);
        }
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

    private void setEventAsProcessed(UUID eventId, UUID correlationId, String eventType) {
        processedEventsRepository.save(new ProcessedEvent(
                eventId,
                correlationId,
                eventType
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

    private void validateUserRegisteredFromAuthEvent(UserRegisteredFromAuthEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
        if (event.details() == null) {
            throw new IllegalArgumentException("event.details must not be null");
        }
        if (!StringUtils.hasText(event.details().username())) {
            throw new IllegalArgumentException("event.details.username must not be blank");
        }
        if (!StringUtils.hasText(event.details().email())) {
            throw new IllegalArgumentException("event.details.email must not be blank");
        }
    }

    private void validateUserInfoFromAuthUpdatedEvent(UserInfoFromAuthUpdatedEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
        if (event.details() == null) {
            throw new IllegalArgumentException("event.details must not be null");
        }
        if (!StringUtils.hasText(event.details().username())) {
            throw new IllegalArgumentException("event.details.username must not be blank");
        }
        if (!StringUtils.hasText(event.details().email())) {
            throw new IllegalArgumentException("event.details.email must not be blank");
        }
    }

    private void validateUserDeletedFromAuthEvent(UserDeletedFromAuthEvent event) {
        validateAuthEventEnvelope(event, event != null ? event.userId() : null, event != null ? event.type() : null);
    }

    private void validateUserRegisteredEvent(UserRegisteredEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    private void validateUserDeletedEvent(UserDeletedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.id() == null) {
            throw new IllegalArgumentException("event.id must not be null");
        }
        if (event.correlationId() == null) {
            throw new IllegalArgumentException("event.correlationId must not be null");
        }
        if (event.userId() == null) {
            throw new IllegalArgumentException("event.userId must not be null");
        }
    }

    private void validateAuthEventEnvelope(Object event, String userId, String type) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("event.type must not be blank");
        }
        parseUuid(userId, "event.userId");
    }

    private UUID parseUuid(String rawUuid, String fieldName) {
        if (!StringUtils.hasText(rawUuid)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", exception);
        }
    }

    private UUID buildDeterministicUuid(String namespace, String... components) {
        var seed = namespace + "|" + String.join("|", components);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
