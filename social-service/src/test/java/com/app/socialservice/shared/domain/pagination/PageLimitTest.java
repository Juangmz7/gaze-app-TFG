package com.app.socialservice.shared.domain.pagination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.socialservice.shared.domain.exception.InvalidPageLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageLimitTest {

    @Test
    void shouldDefaultToTwentyWhenNoLimitIsSent() {
        assertThat(PageLimit.parseOptional(null)).isEqualTo(new PageLimit(PageLimit.DEFAULT));
        assertThat(PageLimit.DEFAULT).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "20", "50", "07"})
    void shouldAcceptWholeNumbersFromOneToFifty(String raw) {
        assertThat(PageLimit.parseOptional(raw).value()).isEqualTo(Integer.parseInt(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "999", "-1", "1000", "1.5", "abc", "", " ", " 5", "5 ", "+5", "1e1"})
    void shouldRejectAnythingElse(String raw) {
        assertThatThrownBy(() -> PageLimit.parseOptional(raw))
                .isInstanceOf(InvalidPageLimitException.class);
    }

    @Test
    void shouldRejectOutOfRangeValuesOnConstruction() {
        assertThatThrownBy(() -> new PageLimit(0)).isInstanceOf(InvalidPageLimitException.class);
        assertThatThrownBy(() -> new PageLimit(51)).isInstanceOf(InvalidPageLimitException.class);
    }

    @Test
    void shouldFetchOneRowMoreThanThePage() {
        assertThat(new PageLimit(20).lookaheadSize()).isEqualTo(21);
    }
}
