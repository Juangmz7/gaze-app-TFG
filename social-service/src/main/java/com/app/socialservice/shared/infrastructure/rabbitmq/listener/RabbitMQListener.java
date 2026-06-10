package com.app.socialservice.shared.infrastructure.rabbitmq;

import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.application.service.UserService;
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

    @RabbitListener(queues = "${rabbitmq.queue.user.register}")
    public void onUserRegister(UserRegisteredFromAuthEvent event) {
            log.info("UserRegistered event received from q.social-service.user.register");

            UUID commandId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();

            UserRegisterCommand command =
                    userRegisterCommandMapper.toCommand(
                            commandId,
                            correlationId,
                            event
                    );
        try {
            userService.registerUser(command);
        } catch (Exception e) {
            log.warn("Error processing user registration message, correlationId={}",
                    command, e);
        }
    }

}
