package com.app.socialservice.shared.infrastructure.rabbitmq;

import com.app.socialservice.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class RabbitMQListener {

    private final UserService userService;

    @RabbitListener(queues = "${rabbitmq.queue.user.register}")
    public void onUserRegister(UserRegisteredFromAuthEvent event) {
        try {
            var command = eventMapper.toRegisterCommand(event);
            userService.registerUser(command);
        } catch (Exception e) {
            log.warn("Error processing user registration message, correlationId={}",
                    command.correlationId(), e);
        }
    }

    // TODO: Listener for


}
