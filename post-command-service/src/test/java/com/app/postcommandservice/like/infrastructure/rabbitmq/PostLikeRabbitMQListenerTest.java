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
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.commentlike.application.usecase.ValidateCommentLikeUseCase;
import com.app.postcommandservice.commentlike.application.usecase.ValidateCommentUnlikeUseCase;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.usecase.ProcessPostViewUseCase;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

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
    private ProcessPostViewUseCase processPostViewUseCase;

    @Mock
    private ValidateCommentLikeUseCase validateCommentLikeUseCase;

    @Mock
    private ValidateCommentUnlikeUseCase validateCommentUnlikeUseCase;

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
                processPostViewUseCase,
                validateCommentLikeUseCase,
                validateCommentUnlikeUseCase,
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

    @Test
    void shouldProcessValidateCommentLikeCommandAndMarkItAsProcessed() {
        var command = commentLikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(false);

        listener.onValidateCommentLike(command);

        verify(validateCommentLikeUseCase).validateAndCreateLike(command);
        verify(processedEventsRepository).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidateCommentLikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicateValidateCommentLikeCommand() {
        var command = commentLikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(true);

        listener.onValidateCommentLike(command);

        verify(validateCommentLikeUseCase, never()).validateAndCreateLike(command);
        verify(processedEventsRepository, never()).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidateCommentLikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldProcessValidateCommentUnlikeCommandAndMarkItAsProcessed() {
        var command = commentUnlikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(false);

        listener.onValidateCommentUnlike(command);

        verify(validateCommentUnlikeUseCase).validateAndDeleteLike(command);
        verify(processedEventsRepository).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidateCommentUnlikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicateValidateCommentUnlikeCommand() {
        var command = commentUnlikeCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(true);

        listener.onValidateCommentUnlike(command);

        verify(validateCommentUnlikeUseCase, never()).validateAndDeleteLike(command);
        verify(processedEventsRepository, never()).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ValidateCommentUnlikeCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldProcessPostViewCommandAndMarkItAsProcessed() {
        var command = postViewCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(false);

        listener.onProcessPostView(command);

        verify(processPostViewUseCase).process(command);
        verify(processedEventsRepository).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ProcessPostViewCommand.class.getSimpleName()
        );
    }

    @Test
    void shouldSkipDuplicatePostViewCommand() {
        var command = postViewCommand();
        when(processedEventsRepository.existsById(command.id())).thenReturn(true);

        listener.onProcessPostView(command);

        verify(processPostViewUseCase, never()).process(command);
        verify(processedEventsRepository, never()).insertIfAbsent(
                command.id(),
                command.correlationId(),
                ProcessPostViewCommand.class.getSimpleName()
        );
    }

    private ValidatePostLikeCommand command() {
        return new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.HOME_FEED,
                1
        );
    }

    private ValidatePostUnlikeCommand unlikeCommand() {
        return new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.SEARCH,
                4
        );
    }

    private ProcessPostViewCommand postViewCommand() {
        return new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostViewSource.HOME_FEED,
                1,
                1200,
                600,
                50,
                PostViewExitReason.NAVIGATED_AWAY
        );
    }

    private ValidateCommentLikeCommand commentLikeCommand() {
        return new ValidateCommentLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommentLikeSource.USER_PROFILE,
                5
        );
    }

    private ValidateCommentUnlikeCommand commentUnlikeCommand() {
        return new ValidateCommentUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CommentLikeSource.SEARCH,
                7
        );
    }
}
