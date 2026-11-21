package com.app.postcommandservice.shared.infrastructure.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationSucceededEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;

import static org.assertj.core.api.Assertions.assertThat;

class EventMessageRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    EventMessageRuntimeHintsTest() {
        new EventMessageRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void shouldLetJacksonReadTheRecordComponentsOfTheOutboxEvents() throws NoSuchMethodException {
        // the event the native image failed to serialize after a media verification
        assertThat(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(PostMediaUploadValidationSucceededEvent.class.getMethod("postId"))).accepts(hints);
        // commands go through the outbox too
        assertThat(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(ValidatePostLikeCommand.class.getMethod("postId"))).accepts(hints);
    }

    @Test
    void shouldCoverTheNestedPayloadTypes() throws NoSuchMethodException {
        assertThat(EventMessageRuntimeHints.eventMessageTypes(getClass().getClassLoader()))
                .contains(PostMediaUploadedEvent.class);
        assertThat(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(PostMediaUploadedMediaPayload.class.getMethod("id"))).accepts(hints);
    }
}
