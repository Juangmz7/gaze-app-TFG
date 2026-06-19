package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.service.UserNodeService;
import com.app.socialservice.user.application.service.UserService;
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

}
