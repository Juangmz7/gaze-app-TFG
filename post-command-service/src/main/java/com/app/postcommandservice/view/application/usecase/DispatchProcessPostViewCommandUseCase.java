package com.app.postcommandservice.view.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.repository.PostViewCommandPublisher;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

@Service
@RequiredArgsConstructor
public class DispatchProcessPostViewCommandUseCase {

    private final PostViewCommandPublisher postViewCommandPublisher;

    public void dispatch(
            UUID viewId,
            UUID postId,
            UUID userId,
            PostViewSource source,
            int feedPosition,
            int durationMs,
            int timeWatchedMs,
            int completionPercent,
            PostViewExitReason exitReason) {
        var command = new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                viewId,
                postId,
                userId,
                source,
                feedPosition,
                durationMs,
                timeWatchedMs,
                completionPercent,
                exitReason
        );

        postViewCommandPublisher.publish(command);
    }
}
