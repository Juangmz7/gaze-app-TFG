package com.app.socialservice.user.infrastructure.mapper;

import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserEventMapperTest {

    private final UserEventMapper userEventMapper = Mappers.getMapper(UserEventMapper.class);

    @Test
    void shouldMapUserBioIntoUserRegisteredEvent() {
        var userId = UUID.randomUUID();
        var occurredOn = Instant.now();
        var user = buildUser(userId);

        var event = userEventMapper.toUserRegisteredEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                user,
                occurredOn
        );

        assertThat(event.bio()).isNotNull();
        assertThat(event.bio().description()).isEqualTo("Domain bio");
        assertThat(event.bio().socialMedia()).containsEntry("github", "domain-user");
    }

    @Test
    void shouldMapUserBioIntoUserUpdatedEvent() {
        var userId = UUID.randomUUID();
        var occurredOn = Instant.now();
        var user = buildUser(userId);

        var event = userEventMapper.toUserUpdated(
                UUID.randomUUID(),
                UUID.randomUUID(),
                user,
                occurredOn
        );

        assertThat(event.bio()).isNotNull();
        assertThat(event.bio().description()).isEqualTo("Domain bio");
        assertThat(event.bio().socialMedia()).containsEntry("github", "domain-user");
        assertThat(event.accountStatus()).isEqualTo(UserAccountStatus.ACCEPTED.name());
    }

    private User buildUser(UUID userId) {
        var user = new User(
                new UserId(userId),
                new Username("domain-user"),
                new Email("domain-user@example.com")
        );
        user.setBio(new UserBio("Domain bio", Map.of("github", "domain-user")));
        user.setAccountStatus(UserAccountStatus.ACCEPTED);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }
}
