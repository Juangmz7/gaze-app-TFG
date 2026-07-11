package com.app.socialservice.user.application.cache;

import java.util.Map;
import java.util.UUID;

import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheNamesTest {

    @Test
    void shouldBuildExpectedCacheKeys() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var command = new UpdateOwnUserProfileCommand(targetUserId, "bio", null, Map.of());

        assertThat(CacheNames.ownProfileKey(targetUserId)).isEqualTo(targetUserId.toString());
        assertThat(CacheNames.ownProfileKey(command)).isEqualTo(targetUserId.toString());
        assertThat(CacheNames.publicProfileKey(requesterUserId, targetUserId))
                .isEqualTo(requesterUserId + ":" + targetUserId);
    }

    @Test
    void shouldRejectNullArgumentsWhenBuildingCacheKeys() {
        assertThatThrownBy(() -> CacheNames.ownProfileKey((UUID) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");

        assertThatThrownBy(() -> CacheNames.ownProfileKey((UpdateOwnUserProfileCommand) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command must not be null");

        assertThatThrownBy(() -> CacheNames.publicProfileKey(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requesterUserId must not be null");
    }
}
