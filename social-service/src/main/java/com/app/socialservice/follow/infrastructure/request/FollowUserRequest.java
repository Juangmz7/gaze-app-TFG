package com.app.socialservice.follow.infrastructure.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record FollowUserRequest(@NotNull UUID followedUserId) {
}
