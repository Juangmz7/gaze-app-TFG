package com.app.postcommandservice.like.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeCommandPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DispatchValidatePostUnlikeCommandUseCaseTest {

    @Mock
    private PostLikeCommandPublisher postLikeCommandPublisher;

    @Captor
    private ArgumentCaptor<ValidatePostUnlikeCommand> commandCaptor;

    @InjectMocks
    private DispatchValidatePostUnlikeCommandUseCase dispatchValidatePostUnlikeCommandUseCase;

    @Test
    void shouldPublishValidatePostUnlikeCommandWithGeneratedEnvelopeAndExpectedPayload() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        dispatchValidatePostUnlikeCommandUseCase.dispatch(postId, userId);

        verify(postLikeCommandPublisher).publish(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();
    }
}
