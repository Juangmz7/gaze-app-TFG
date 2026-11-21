package com.app.postcommandservice.collab.domain.model.valueobj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.postcommandservice.collab.domain.exception.CollabJoinInstructionsTooLongException;
import com.app.postcommandservice.shared.domain.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollabJoinInstructionsTest {

    @Test
    void shouldTrimTheTextWhenItIsValid() {
        var joinInstructions = CollabJoinInstructions.ofNullable("  Join https://discord.gg/abc\n  ");

        assertThat(joinInstructions).isNotNull();
        assertThat(joinInstructions.value()).isEqualTo("Join https://discord.gg/abc");
    }

    @Test
    void shouldKeepInnerLineBreaksOfAMultiLineText() {
        var joinInstructions = CollabJoinInstructions.ofNullable("Welcome!\n\n1. Chat: https://chat\n2. Repo: https://repo");

        assertThat(joinInstructions.value()).isEqualTo("Welcome!\n\n1. Chat: https://chat\n2. Repo: https://repo");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t "})
    void shouldTreatNullOrBlankTextAsNoInstructions(String rawValue) {
        assertThat(CollabJoinInstructions.ofNullable(rawValue)).isNull();
    }

    @Test
    void shouldAcceptTextOfExactlyTheMaximumLength() {
        var joinInstructions = CollabJoinInstructions.ofNullable("a".repeat(CollabJoinInstructions.MAX_LENGTH));

        assertThat(joinInstructions.value()).hasSize(4000);
    }

    @Test
    void shouldMeasureTheLimitAfterTrimming() {
        var joinInstructions = CollabJoinInstructions.ofNullable("  " + "a".repeat(4000) + "  ");

        assertThat(joinInstructions.value()).hasSize(4000);
    }

    @Test
    void shouldCountCharactersNotUtf16UnitsSoEmojisCountOnce() {
        var emojis = "🚀".repeat(4000);

        assertThat(CollabJoinInstructions.ofNullable(emojis).value()).isEqualTo(emojis);
    }

    @Test
    void shouldThrowJoinInstructionsTooLongWhenTextExceedsTheMaximumLength() {
        assertThatThrownBy(() -> CollabJoinInstructions.ofNullable("a".repeat(4001)))
                .isInstanceOf(CollabJoinInstructionsTooLongException.class)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("JOIN_INSTRUCTIONS_TOO_LONG")
                .hasMessageContaining("4000");
    }

    @Test
    void shouldRejectBlankTextInTheConstructorBecauseAbsenceIsNull() {
        assertThatThrownBy(() -> new CollabJoinInstructions("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollabJoinInstructions(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeNullAsTheValueOfAbsentInstructions() {
        assertThat(CollabJoinInstructions.valueOrNull(null)).isNull();
        assertThat(CollabJoinInstructions.valueOrNull(new CollabJoinInstructions("x"))).isEqualTo("x");
    }
}
