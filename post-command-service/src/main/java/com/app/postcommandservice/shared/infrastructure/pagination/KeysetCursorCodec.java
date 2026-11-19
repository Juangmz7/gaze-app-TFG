package com.app.postcommandservice.shared.infrastructure.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.shared.domain.exception.InvalidCursorException;
import com.app.postcommandservice.shared.domain.model.pagination.KeysetPosition;

/**
 * Opaque keyset cursor: unpadded base64url of {@code <ISO-8601 instant>|<uuid>}. Clients must
 * treat it as an opaque token; anything that does not decode back to a valid position is
 * rejected as {@code invalid_cursor}.
 */
@Component
public class KeysetCursorCodec {

    private static final int MAX_CURSOR_LENGTH = 256;
    private static final String SEPARATOR = "|";

    public String encode(KeysetPosition position) {
        if (position == null) {
            return null;
        }
        var raw = position.createdAt().toString() + SEPARATOR + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns {@code null} for an absent cursor (first page). */
    public KeysetPosition decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separatorIndex = raw.indexOf(SEPARATOR);
            if (separatorIndex <= 0 || separatorIndex != raw.lastIndexOf(SEPARATOR)) {
                throw invalidCursor();
            }
            var createdAt = Instant.parse(raw.substring(0, separatorIndex));
            var rawId = raw.substring(separatorIndex + 1);
            var id = UUID.fromString(rawId);
            // UUID.fromString accepts non-canonical forms such as "1-1-1-1-1"
            if (!id.toString().equals(rawId.toLowerCase(Locale.ROOT))) {
                throw invalidCursor();
            }
            return new KeysetPosition(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private static InvalidCursorException invalidCursor() {
        return new InvalidCursorException("cursor is malformed");
    }
}
