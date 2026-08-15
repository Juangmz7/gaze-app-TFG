package com.app.postcommandservice.like.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeCommandPublisher;
import com.app.postcommandservice.like.domain.model.PostLikeSource;

@Service
@RequiredArgsConstructor
public class DispatchValidatePostUnlikeCommandUseCase {

    private final PostLikeCommandPublisher postLikeCommandPublisher;

    public void dispatch(UUID postId, UUID userId, PostLikeSource source, int feedPosition) {
        var command = new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                postId,
                userId,
                source,
                feedPosition
        );

        postLikeCommandPublisher.publish(command);
    }
}
