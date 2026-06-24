package com.app.socialservice.follow.application.commands;

import java.util.UUID;

public record UnfollowUserCommand(UUID followerUserId, UUID followedUserId) {

    public UnfollowUserCommand {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }
    }
}
