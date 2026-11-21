package com.app.socialservice.shared.domain.pagination;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.app.socialservice.shared.domain.exception.InvalidCursorException;

/**
 * The sort key (createdAt DESC, id DESC) of the last row of a keyset page. Clients get it as an
 * opaque base64url token and send it back unchanged to get the next page: the next page holds the
 * rows whose (createdAt, id) is lower than this key.
 */
public record KeysetCursor(Instant createdAt, UUID id) {

    static final int MAX_ENCODED_LENGTH = 128;
    // The range PostgreSQL's timestamptz and the four-digit year format can both hold
    static final Instant MIN_CREATED_AT = Instant.parse("0001-01-01T00:00:00Z");
    static final Instant MAX_CREATED_AT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private static final Pattern SORT_KEY = Pattern.compile(
            "(-?\\d{1,12})\\.(\\d{9}):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public KeysetCursor {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (createdAt.isBefore(MIN_CREATED_AT) || createdAt.isAfter(MAX_CREATED_AT)) {
            throw new IllegalArgumentException("createdAt is out of range: " + createdAt);
        }
    }

    /**
     * No cursor means the first page; anything else must be a token this class encoded.
     */
    public static Optional<KeysetCursor> parseOptional(String encoded) {
        return encoded == null ? Optional.empty() : Optional.of(decode(encoded));
    }

    public static KeysetCursor decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw invalidCursor();
        }
        try {
            var matcher = SORT_KEY.matcher(new String(DECODER.decode(encoded), StandardCharsets.US_ASCII));
            if (!matcher.matches()) {
                throw invalidCursor();
            }
            var cursor = new KeysetCursor(
                    Instant.ofEpochSecond(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))),
                    UUID.fromString(matcher.group(3))
            );
            // Only the exact token encode() issues: no padding, no alternative spelling of the same key
            if (!cursor.encode().equals(encoded)) {
                throw invalidCursor();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    public String encode() {
        var sortKey = String.format(Locale.ROOT, "%d.%09d:%s", createdAt.getEpochSecond(), createdAt.getNano(), id);
        return ENCODER.encodeToString(sortKey.getBytes(StandardCharsets.US_ASCII));
    }

    private static InvalidCursorException invalidCursor() {
        return new InvalidCursorException("cursor is malformed: pass back the nextCursor of the previous page");
    }
}
