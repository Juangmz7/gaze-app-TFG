package com.app.socialservice.shared.application.pagination;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;

/**
 * One page of a keyset-paginated list. nextCursor is the encoded sort key of the last item, or
 * null on the last page.
 */
public record KeysetPage<T>(List<T> items, String nextCursor) {

    public KeysetPage {
        Objects.requireNonNull(items, "items must not be null");
        items = List.copyOf(items);
    }

    /**
     * Builds the page from rows fetched with {@link PageLimit#lookaheadSize()}: the extra row
     * only tells that another page follows and is not returned.
     */
    public static <T> KeysetPage<T> fromLookahead(
            List<T> rows,
            PageLimit limit,
            Function<T, KeysetCursor> sortKeyOf
    ) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(sortKeyOf, "sortKeyOf must not be null");
        if (rows.size() > limit.lookaheadSize()) {
            throw new IllegalArgumentException(String.format(
                    "rows must not exceed the lookahead size %d, got %d", limit.lookaheadSize(), rows.size()));
        }

        if (rows.size() <= limit.value()) {
            return new KeysetPage<>(rows, null);
        }
        var pageItems = rows.subList(0, limit.value());
        var nextCursor = sortKeyOf.apply(pageItems.getLast()).encode();
        return new KeysetPage<>(pageItems, nextCursor);
    }

    public <R> KeysetPage<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return new KeysetPage<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
