package com.app.postcommandservice.shared.domain.model.pagination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.postcommandservice.shared.domain.exception.InvalidPageLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageLimitTest {

    @Test
    void shouldDefaultToTwentyWhenAbsent() {
        assertThat(PageLimit.parse(null).value()).isEqualTo(20);
    }

    @Test
    void shouldAcceptBoundsAndFetchOneExtraRow() {
        assertThat(PageLimit.parse("1").value()).isEqualTo(1);
        assertThat(PageLimit.parse("50").fetchSize()).isEqualTo(51);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-3", "abc", "", "2.5", "99999999999"})
    void shouldRejectInvalidLimits(String rawLimit) {
        assertThatThrownBy(() -> PageLimit.parse(rawLimit)).isInstanceOf(InvalidPageLimitException.class);
    }
}
