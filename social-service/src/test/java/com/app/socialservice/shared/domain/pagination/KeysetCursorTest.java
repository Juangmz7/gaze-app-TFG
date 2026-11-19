package com.app.socialservice.shared.domain.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.socialservice.shared.domain.exception.InvalidCursorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetCursorTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-19T10:15:30.123456Z");
    private static final UUID ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    @Test
    void shouldEncodeCreatedAtAndIdAsAnOpaqueBase64UrlToken() {
        var encoded = new KeysetCursor(CREATED_AT, ID).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII))
                .isEqualTo("%d.123456000:%s".formatted(CREATED_AT.getEpochSecond(), ID));
    }

    @Test
    void shouldDecodeWhatItEncodes() {
        var cursor = new KeysetCursor(CREATED_AT, ID);

        var decoded = KeysetCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void shouldRoundTripNanosecondsAndInstantsBeforeTheEpoch() {
        var beforeEpoch = new KeysetCursor(Instant.parse("1969-12-31T23:59:59.000000001Z"), ID);
        var oldest = new KeysetCursor(KeysetCursor.MIN_CREATED_AT, ID);
        var newest = new KeysetCursor(KeysetCursor.MAX_CREATED_AT, ID);

        assertThat(KeysetCursor.decode(beforeEpoch.encode())).isEqualTo(beforeEpoch);
        assertThat(KeysetCursor.decode(oldest.encode())).isEqualTo(oldest);
        assertThat(KeysetCursor.decode(newest.encode())).isEqualTo(newest);
    }

    @Test
    void shouldTreatAMissingCursorAsTheFirstPage() {
        assertThat(KeysetCursor.parseOptional(null)).isEmpty();
        assertThat(KeysetCursor.parseOptional(new KeysetCursor(CREATED_AT, ID).encode()))
                .contains(new KeysetCursor(CREATED_AT, ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "not a cursor",
            "%%%%",
            "abc+/def",
            "YWJj",
            "eyJjcmVhdGVkQXQiOjF9",
    })
    void shouldRejectGarbage(String encoded) {
        assertThatThrownBy(() -> KeysetCursor.decode(encoded))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> KeysetCursor.parseOptional(encoded))
                .isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1763547330.123456000",
            "1763547330.123456000:",
            "1763547330:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "1763547330.123:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "1763547330.1234560000:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "1763547330.123456000:7C9E6679-7425-40DE-944B-E07FC1F90AE7",
            "1763547330.123456000:7c9e6679742540de944be07fc1f90ae7",
            "1763547330.123456000:not-a-uuid",
            "+1763547330.123456000:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "01763547330.123456000:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "999999999999.000000000:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "-999999999999.000000000:7c9e6679-7425-40de-944b-e07fc1f90ae7",
            "1763547330.123456000:7c9e6679-7425-40de-944b-e07fc1f90ae7 ",
            "1763547330.123456000:7c9e6679-7425-40de-944b-e07fc1f90ae7\n",
    })
    void shouldRejectWellFormedBase64ThatIsNotASortKey(String sortKey) {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sortKey.getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> KeysetCursor.decode(encoded))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectAPaddedSpellingOfAValidToken() {
        // "-1.000000001:<uuid>" is 49 bytes, so its padded base64 ends in "=="
        var cursor = new KeysetCursor(Instant.parse("1969-12-31T23:59:59.000000001Z"), ID);
        var padded = Base64.getUrlEncoder().encodeToString(
                new String(Base64.getUrlDecoder().decode(cursor.encode()), StandardCharsets.US_ASCII)
                        .getBytes(StandardCharsets.US_ASCII));

        assertThat(padded).endsWith("=");
        assertThatThrownBy(() -> KeysetCursor.decode(padded))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectATokenLongerThanTheLimit() {
        var tooLong = "A".repeat(KeysetCursor.MAX_ENCODED_LENGTH + 1);

        assertThatThrownBy(() -> KeysetCursor.decode(tooLong))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectMissingOrOutOfRangeValuesOnConstruction() {
        assertThatThrownBy(() -> new KeysetCursor(null, ID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeysetCursor(CREATED_AT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeysetCursor(KeysetCursor.MIN_CREATED_AT.minusNanos(1), ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeysetCursor(KeysetCursor.MAX_CREATED_AT.plusNanos(1), ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
