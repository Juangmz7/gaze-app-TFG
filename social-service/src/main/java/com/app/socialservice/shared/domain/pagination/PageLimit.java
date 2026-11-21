package com.app.socialservice.shared.domain.pagination;

import java.util.regex.Pattern;

import com.app.socialservice.shared.domain.exception.InvalidPageLimitException;

/**
 * How many rows a keyset page returns: 1..50, 20 when the client sends none.
 */
public record PageLimit(int value) {

    public static final int MIN = 1;
    public static final int MAX = 50;
    public static final int DEFAULT = 20;

    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");

    public PageLimit {
        if (value < MIN || value > MAX) {
            throw invalidLimit();
        }
    }

    public static PageLimit parseOptional(String raw) {
        if (raw == null) {
            return new PageLimit(DEFAULT);
        }
        if (!DIGITS.matcher(raw).matches()) {
            throw invalidLimit();
        }
        return new PageLimit(Integer.parseInt(raw));
    }

    /**
     * One row more than the page, so a page knows whether another one follows without a count.
     */
    public int lookaheadSize() {
        return value + 1;
    }

    private static InvalidPageLimitException invalidLimit() {
        return new InvalidPageLimitException(String.format("limit must be a whole number from %d to %d", MIN, MAX));
    }
}
