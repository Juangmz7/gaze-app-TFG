package com.app.postcommandservice.shared.infrastructure.validation;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import com.app.postcommandservice.post.infrastructure.controller.ReportPostViewRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    ValidationRuntimeHintsTest() {
        new ValidationRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void shouldLetHibernateValidatorInvokeAssertTrueMethodsOfRequestRecords() throws NoSuchMethodException {
        // POST /api/posts/{id}/views failed in the native image with MissingReflectionRegistrationError
        var hasValidWatchDuration = ReportPostViewRequest.PlaybackMetricsRequest.class.getMethod("hasValidWatchDuration");

        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(hasValidWatchDuration)).accepts(hints);
    }

    @Test
    void shouldLetHibernateValidatorInvokeConstrainedRecordAccessors() throws NoSuchMethodException {
        // record component constraints propagate to the accessor, which the validator also reads
        var context = ReportPostViewRequest.class.getMethod("context");
        var durationMs = ReportPostViewRequest.PlaybackMetricsRequest.class.getMethod("durationMs");

        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(context)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(durationMs)).accepts(hints);
    }

    @Test
    void shouldIgnoreMethodsWithoutConstraints() throws NoSuchMethodException {
        var toExitReason = ReportPostViewRequest.PlaybackMetricsRequest.class.getMethod("toExitReason");

        assertThat(ValidationRuntimeHints.constrainedMethods(getClass().getClassLoader())).doesNotContain(toExitReason);
    }
}
