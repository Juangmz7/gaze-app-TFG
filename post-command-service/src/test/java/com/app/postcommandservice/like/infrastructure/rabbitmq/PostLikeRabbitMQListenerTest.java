package com.app.postcommandservice.like.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.usecase.ValidatePostLikeUseCase;
import com.app.postcommandservice.like.application.usecase.ValidatePostUnlikeUseCase;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostLikeRabbitMQListenerTest {

    @Mock
    private ValidatePostLikeUseCase validatePostLikeUseCase;

    @Mock
    private ValidatePostUnlikeUseCase validatePostUnlikeUseCase;

    @Mock
    private ProcessedEventsRepository processedEventsRepository;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    private PostLikeRabbitMQListener listener;

    @BeforeEach
    void setUp() {
        listener = new PostLikeRabbitMQListener(
                validatePostLikeUseCase,
                validatePostUnlikeUseCase,
                processedEventsRepository,
                rabbitMQProperties
        );
    }

    @Test
    void shouldProcessValidatePostLikeCommandAndMarkItAsProcessed() {
        var command = command();
        when(processedEventsRepository.existsById(command.id())).thenReturn(false);

        listener.onValidatePostLike(command);

        verify(validatePostLikeUseCase).validateAndCreateLike(command);
        verify(processedEventsRepository).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidatePostLikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicateValidatePostLikeCommand() {
        var command = command();
        when(processedEventsRepository.existsById(command.id())).thenReturn(true);

        listener.onValidatePostLike(command);

        verify(validatePostLikeUseCase, never()).validateAndCreateLike(command);
        verify(processedEventsRepository, never()).insertIfAbsent(command.id(), command.correlationId(), 
                ValidatePostLikeCommand.class.getSimpleName());
    }

    @Test
    void shouldProcessValidatePostUnlikeCommandAndMarkItAsProcessed() {
        var command = unlikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(false);

        listener.onValidatePostUnlike(command);

        verify(validatePostUnlikeUseCase).validateAndDeleteLike(command);
        verify(processedEventsRepository).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidatePostUnlikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicateValidatePostUnlikeCommand() {
        var command = unlikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(true);

        listener.onValidatePostUnlike(command);

        verify(validatePostUnlikeUseCase, never()).validateAndDeleteLike(command);
        verify(processedEventsRepository, never()).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidatePostUnlikeCommand.class.getSimpleName()
        );
    }

    private ValidatePostLikeCommand command() {
        return new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }

    private ValidatePostUnlikeCommand unlikeCommand() {
        return new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }
}
