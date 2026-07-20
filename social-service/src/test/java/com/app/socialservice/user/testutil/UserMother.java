package com.app.socialservice.user.testutil;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.entity.UserBioEmbeddable;
import com.app.socialservice.user.infrastructure.entity.UserEntity;

public final class UserMother {

    private UserMother() {
    }

    public static User accepted() {
        return accepted(UUID.randomUUID(), "accepted-user");
    }

    public static User accepted(UUID userId, String username) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");

        return new User(
                new UserId(userId),
                new Username(username),
                new Email(username + "@example.com")
        );
    }

    public static UserEntity acceptedEntity(UUID userId, String username) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");

        return UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build();
    }

    public static UserEntity entity(
            UUID userId,
            String username,
            UserAccountStatus accountStatus,
            Instant createdAt,
            String description,
            Map<String, String> socialMedia,
            String pictureUrl
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");

        UserBioEmbeddable bio = null;
        if (description != null || socialMedia != null) {
            bio = UserBioEmbeddable.builder()
                    .description(description)
                    .socialMedia(socialMedia)
                    .build();
        }

        return UserEntity.builder()
                .id(userId)
                .username(username)
                .email(username + "@example.com")
                .pictureUrl(pictureUrl)
                .bio(bio)
                .accountStatus(accountStatus)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }
}
