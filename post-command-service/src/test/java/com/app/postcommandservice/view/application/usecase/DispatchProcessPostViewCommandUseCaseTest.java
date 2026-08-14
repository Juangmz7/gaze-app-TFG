package com.app.postcommandservice.view.application.usecase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.repository.PostViewCommandPublisher;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DispatchProcessPostViewCommandUseCaseTest {

    @Mock
    private PostViewCommandPublisher postViewCommandPublisher;

    @Captor
    private ArgumentCaptor<ProcessPostViewCommand> commandCaptor;

    @InjectMocks
    private DispatchProcessPostViewCommandUseCase dispatchProcessPostViewCommandUseCase;

    @Test
    void shouldPublishProcessPostViewCommandWithGeneratedEnvelopeAndExpectedPayload() {
        var postId = UUID.randomUUID();
        var viewId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        dispatchProcessPostViewCommandUseCase.dispatch(
                viewId,
                postId,
                userId,
                PostViewSource.SEARCH,
                4,
                3000,
                2100,
                70,
                PostViewExitReason.VIDEO_COMPLETED
        );

        verify(postViewCommandPublisher).publish(commandCaptor.capture());
        var command = commandCaptor.getValue();
        assertThat(command.viewId()).isEqualTo(viewId);
        assertThat(command.postId()).isEqualTo(postId);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.source()).isEqualTo(PostViewSource.SEARCH);
        assertThat(command.feedPosition()).isEqualTo(4);
        assertThat(command.durationMs()).isEqualTo(3000);
        assertThat(command.timeWatchedMs()).isEqualTo(2100);
        assertThat(command.completionPercent()).isEqualTo(70);
        assertThat(command.exitReason()).isEqualTo(PostViewExitReason.VIDEO_COMPLETED);
        assertThat(command.id()).isNotNull();
        assertThat(command.correlationId()).isNotNull();
        assertThat(command.occurredAt()).isNotNull();
    }
}
