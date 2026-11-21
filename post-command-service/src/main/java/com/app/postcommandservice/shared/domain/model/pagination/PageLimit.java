package com.app.postcommandservice.shared.domain.model.pagination;

import com.app.postcommandservice.shared.domain.exception.InvalidPageLimitException;

/**
 * Page size of a keyset list: 1..{@value #MAX}, {@value #DEFAULT} when the client sends none.
 */
public record PageLimit(int value) {

    public static final int DEFAULT = 20;
    public static final int MAX = 50;

    public PageLimit {
        if (value < 1 || value > MAX) {
            throw new InvalidPageLimitException("limit must be between 1 and " + MAX);
        }
    }

    public static PageLimit parse(String rawLimit) {
        if (rawLimit == null) {
            return new PageLimit(DEFAULT);
        }
        try {
            return new PageLimit(Integer.parseInt(rawLimit.trim()));
        } catch (NumberFormatException exception) {
            throw new InvalidPageLimitException("limit must be an integer between 1 and " + MAX);
        }
    }

    /** Rows to read so the presence of a next page is known without a count query. */
    public int fetchSize() {
        return value + 1;
    }
}
