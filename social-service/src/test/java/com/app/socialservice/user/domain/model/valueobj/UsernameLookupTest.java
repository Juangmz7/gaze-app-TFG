package com.app.socialservice.user.domain.model.valueobj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.socialservice.user.domain.exception.InvalidUsernameLookupException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernameLookupTest {

    @Test
    void shouldKeepTheUsernameInLowerCaseSoTheLookupIsCaseInsensitive() {
        assertThat(new UsernameLookup("Jane.Doe_42").value()).isEqualTo("jane.doe_42");
        assertThat(new UsernameLookup("JANE.DOE_42")).isEqualTo(new UsernameLookup("jane.doe_42"));
    }

    @Test
    void shouldAcceptUsernamesFromOneToSixtyFourCharacters() {
        assertThat(new UsernameLookup("a").value()).isEqualTo("a");
        assertThat(new UsernameLookup("a".repeat(UsernameLookup.MAX_LENGTH)).value()).hasSize(64);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "jane doe",
            "jane-doe",
            "@jane",
            "jane/doe",
            "jane%2Fdoe",
            "jañe",
            "jane\n",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void shouldRejectUsernamesOutsideTheLengthOrTheUsernameCharset(String username) {
        assertThatThrownBy(() -> new UsernameLookup(username))
                .isInstanceOf(InvalidUsernameLookupException.class)
                .hasMessageContaining("username must be 1 to 64 characters");
    }
}
