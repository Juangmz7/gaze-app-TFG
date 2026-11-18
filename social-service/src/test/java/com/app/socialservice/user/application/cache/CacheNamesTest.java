package com.app.socialservice.user.application.cache;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheNamesTest {

    @Test
    void shouldKeyEveryCacheByTheUserIdAlone() {
        var userId = UUID.randomUUID();
        var command = new UpdateOwnUserProfileCommand(userId, "bio", null, Map.of());

        assertThat(CacheNames.userKey(userId)).isEqualTo(userId.toString());
        assertThat(CacheNames.userKey(command)).isEqualTo(userId.toString());
    }

    @Test
    void shouldRejectNullArgumentsWhenBuildingCacheKeys() {
        assertThatThrownBy(() -> CacheNames.userKey((UUID) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");

        assertThatThrownBy(() -> CacheNames.userKey((UpdateOwnUserProfileCommand) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be null");
    }
}
