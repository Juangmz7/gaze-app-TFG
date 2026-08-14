package com.app.postcommandservice.like.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.usecase.ValidatePostLikeUseCase;
import com.app.postcommandservice.like.application.usecase.ValidatePostUnlikeUseCase;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.listener.AbstractRabbitMQListenerSupport;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

@Slf4j
@Component
@RabbitListener(queues = "${rabbitmq.queue.post}")
public class PostLikeRabbitMQListener extends AbstractRabbitMQListenerSupport {

    private final ValidatePostLikeUseCase validatePostLikeUseCase;
    private final ValidatePostUnlikeUseCase validatePostUnlikeUseCase;

    public PostLikeRabbitMQListener(
            ValidatePostLikeUseCase validatePostLikeUseCase,
            ValidatePostUnlikeUseCase validatePostUnlikeUseCase,
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        super(processedEventsRepository, rabbitMQProperties);
        this.validatePostLikeUseCase = validatePostLikeUseCase;
        this.validatePostUnlikeUseCase = validatePostUnlikeUseCase;
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

    @Transactional
    @RabbitHandler
    public void onValidatePostUnlike(ValidatePostUnlikeCommand command) {
        validateCommand(command);
        if (isEventAlreadyProcessed(command.id(), command.correlationId())) {
            log.warn("Detected duplicate validate post unlike command {}, skipping", command.id());
            return;
        }

        validatePostUnlikeUseCase.validateAndDeleteLike(command);
        setEventAsProcessed(command.id(), command.correlationId(), ValidatePostUnlikeCommand.class.getSimpleName());
    }

    @RabbitHandler(isDefault = true)
    public void onUnsupportedPostCommand(Object ignored) {
        throw rejectToDlq(new IllegalArgumentException("Unsupported post command payload"));
    }

    private void validateCommand(ValidatePostLikeCommand command) {
        validateCommand(
                command == null ? null : command.id(),
                command == null ? null : command.correlationId(),
                command == null ? null : command.occurredAt(),
                command == null ? null : command.postId(),
                command == null ? null : command.userId()
        );
    }

    private void validateCommand(ValidatePostUnlikeCommand command) {
        validateCommand(
                command == null ? null : command.id(),
                command == null ? null : command.correlationId(),
                command == null ? null : command.occurredAt(),
                command == null ? null : command.postId(),
                command == null ? null : command.userId()
        );
    }

    private void validateCommand(
            UUID commandId,
            UUID correlationId,
            Instant occurredAt,
            UUID postId,
            UUID userId) {
        if (commandId == null && correlationId == null && occurredAt == null && postId == null && userId == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (commandId == null) {
            throw new IllegalArgumentException("command.id must not be null");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("command.correlationId must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("command.occurredAt must not be null");
        }
        if (postId == null) {
            throw new IllegalArgumentException("command.postId must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
        if (!StringUtils.hasText(commandId.toString())
                || !StringUtils.hasText(correlationId.toString())
                || !StringUtils.hasText(postId.toString())
                || !StringUtils.hasText(userId.toString())) {
            throw new IllegalArgumentException("command identifiers must not be blank");
        }
    }
}
