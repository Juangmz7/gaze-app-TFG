package com.app.postcommandservice.shared.infrastructure.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.postcommandservice.shared.domain.exception.InvalidCursorException;
import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetCursorCodecTest {

    private final KeysetCursorCodec codec = new KeysetCursorCodec();

    @Test
    void shouldRoundTripPosition() {
        var position = new KeysetPosition(Instant.parse("2026-11-19T10:15:30.123456Z"), UUID.randomUUID());

        var cursor = codec.encode(position);

        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(codec.decode(cursor)).isEqualTo(position);
    }

    @Test
    void shouldTreatAbsentCursorAsFirstPage() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.encode(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not base64 !!", "%%%"})
    void shouldRejectUndecodableCursor(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor)).isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "no-separator",
            "2026-11-19T10:15:30Z|not-a-uuid",
            "yesterday|3f2b8c1e-0000-4000-8000-000000000000",
            "2026-11-19T10:15:30Z|1-1-1-1-1",
            "2026-11-19T10:15:30Z|3f2b8c1e-0000-4000-8000-000000000000|extra",
            "|3f2b8c1e-0000-4000-8000-000000000000"
    })
    void shouldRejectWellEncodedButMalformedContent(String raw) {
        var cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(cursor)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectOversizedCursor() {
        assertThatThrownBy(() -> codec.decode("a".repeat(300))).isInstanceOf(InvalidCursorException.class);
    }
}
