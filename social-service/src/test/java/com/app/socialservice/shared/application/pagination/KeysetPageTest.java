package com.app.socialservice.shared.application.pagination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetPageTest {

    private static final Instant NEWEST = Instant.parse("2026-11-19T10:00:00Z");

    private record Row(Instant createdAt, UUID id) {
        KeysetCursor sortKey() {
            return new KeysetCursor(createdAt, id);
        }
    }

    @Test
    void shouldReturnTheRowsWithoutACursorWhenTheyFitInThePage() {
        var rows = rows(3);

        var page = KeysetPage.fromLookahead(rows, new PageLimit(5), Row::sortKey);

        assertThat(page.items()).isEqualTo(rows);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldReturnNoCursorWhenTheRowsFillThePageExactly() {
        var rows = rows(5);

        var page = KeysetPage.fromLookahead(rows, new PageLimit(5), Row::sortKey);

        assertThat(page.items()).isEqualTo(rows);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldDropTheLookaheadRowAndPointTheCursorAtTheLastReturnedRow() {
        var rows = rows(6);

        var page = KeysetPage.fromLookahead(rows, new PageLimit(5), Row::sortKey);

        assertThat(page.items()).isEqualTo(rows.subList(0, 5));
        assertThat(KeysetCursor.decode(page.nextCursor())).isEqualTo(rows.get(4).sortKey());
    }

    @Test
    void shouldRejectMoreRowsThanTheLookahead() {
        assertThatThrownBy(() -> KeysetPage.fromLookahead(rows(7), new PageLimit(5), Row::sortKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMapItemsAndKeepTheCursor() {
        var page = KeysetPage.fromLookahead(rows(3), new PageLimit(2), Row::sortKey);

        var mapped = page.map(Row::id);

        assertThat(mapped.items()).containsExactly(page.items().get(0).id(), page.items().get(1).id());
        assertThat(mapped.nextCursor()).isEqualTo(page.nextCursor());
    }

    private static List<Row> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Row(NEWEST.minusSeconds(i), UUID.randomUUID()))
                .toList();
    }
}
