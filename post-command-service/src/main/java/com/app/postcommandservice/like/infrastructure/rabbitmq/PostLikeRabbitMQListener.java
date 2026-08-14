package com.app.postcommandservice.like.infrastructure.rabbitmq;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.usecase.ValidatePostLikeUseCase;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.listener.AbstractRabbitMQListenerSupport;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

@Slf4j
@Component
@RabbitListener(queues = "${rabbitmq.queue.post}")
public class PostLikeRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final ValidatePostLikeUseCase validatePostLikeUseCase;

    public PostLikeRabbitMQListener(
            ValidatePostLikeUseCase validatePostLikeUseCase,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.validatePostLikeUseCase = validatePostLikeUseCase;
    }

    @Transactional
    @RabbitHandler
    public void onValidatePostLike(ValidatePostLikeCommand command) {
        validateCommand(command);
        if (isEventAlreadyProcessed(command.id(), command.correlationId())) {
            log.warn("Detected duplicate validate post like command {}, skipping", command.id());
            return;
        }

        validatePostLikeUseCase.validateAndCreateLike(command);
        setEventAsProcessed(command.id(), command.correlationId(), ValidatePostLikeCommand.class.getSimpleName());
    }

    @RabbitHandler(isDefault = true)
    public void onUnsupportedPostCommand(Object ignored) {
        throw rejectToDlq(new IllegalArgumentException("Unsupported post command payload"));
    }

    private void validateCommand(ValidatePostLikeCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.id() == null) {
            throw new IllegalArgumentException("command.id must not be null");
        }
        if (command.correlationId() == null) {
            throw new IllegalArgumentException("command.correlationId must not be null");
        }
        if (command.occurredAt() == null) {
            throw new IllegalArgumentException("command.occurredAt must not be null");
        }
        if (command.postId() == null) {
            throw new IllegalArgumentException("command.postId must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (!StringUtils.hasText(command.id().toString())
                || !StringUtils.hasText(command.correlationId().toString())
                || !StringUtils.hasText(command.postId().toString())
                || !StringUtils.hasText(command.userId().toString())) {
            throw new IllegalArgumentException("command identifiers must not be blank");
        }
    }
}
