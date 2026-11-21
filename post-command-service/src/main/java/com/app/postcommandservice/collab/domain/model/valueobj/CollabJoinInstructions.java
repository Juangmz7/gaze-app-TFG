package com.app.postcommandservice.collab.domain.model.valueobj;

import com.app.postcommandservice.collab.domain.exception.CollabJoinInstructionsTooLongException;

/**
 * Free-form onboarding text the collab ADMIN shares with accepted members (task 61).
 * Always trimmed and never blank: an absent or blank value is represented by {@code null}
 * on the aggregate, see {@link #ofNullable(String)}. The limit counts characters (code points),
 * so an emoji counts once.
 */
public record CollabJoinInstructions(String value) {

    public static final int MAX_LENGTH = 4000;

    public CollabJoinInstructions {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Join instructions must not be blank; use ofNullable for optional values");
        }
        value = value.trim();
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new CollabJoinInstructionsTooLongException(MAX_LENGTH);
        }
    }

    public static CollabJoinInstructions ofNullable(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return new CollabJoinInstructions(rawValue);
    }

    public static String valueOrNull(CollabJoinInstructions joinInstructions) {
        return joinInstructions == null ? null : joinInstructions.value();
    }
}
