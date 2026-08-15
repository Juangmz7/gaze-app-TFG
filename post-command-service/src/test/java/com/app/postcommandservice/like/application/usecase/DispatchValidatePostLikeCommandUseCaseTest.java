package com.app.postcommandservice.like.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeCommandPublisher;
import com.app.postcommandservice.like.domain.model.PostLikeSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DispatchValidatePostLikeCommandUseCaseTest {

    @Mock
    private PostLikeCommandPublisher postLikeCommandPublisher;

    @Captor
    private ArgumentCaptor<ValidatePostLikeCommand> commandCaptor;

    @InjectMocks
    private DispatchValidatePostLikeCommandUseCase dispatchValidatePostLikeCommandUseCase;

    @Test
    void shouldPublishValidatePostLikeCommandWithGeneratedEnvelopeAndExpectedPayload() {
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var source = PostLikeSource.SEARCH;
        var feedPosition = 4;

        dispatchValidatePostLikeCommandUseCase.dispatch(postId, userId, source, feedPosition);

        verify(postLikeCommandPublisher).publish(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.source()).isEqualTo(source);
        assertThat(command.feedPosition()).isEqualTo(feedPosition);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();
    }
}
