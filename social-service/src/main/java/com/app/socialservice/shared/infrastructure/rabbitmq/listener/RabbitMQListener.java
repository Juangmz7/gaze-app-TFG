package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

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

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class RabbitMQListener {

    private final UserService userService;
    private final UserRegisterCommandMapper userRegisterCommandMapper;
    private final UserNodeService userNodeService;

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

}

