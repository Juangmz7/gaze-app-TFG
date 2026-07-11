package com.app.socialservice.user.application.cache;

import java.util.UUID;

import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;

public final class CacheNames {

    public static final String OWN_PROFILE = "user-own-profile";
    public static final String PUBLIC_PROFILE = "user-public-profile";
    public static final String OWN_PROFILE_KEY_BY_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).ownProfileKey(#userId)";
    public static final String OWN_PROFILE_KEY_BY_COMMAND =
            "T(com.app.socialservice.user.application.cache.CacheNames).ownProfileKey(#command)";
    public static final String OWN_PROFILE_KEY_BY_FOLLOWER_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).ownProfileKey(#followerUserId)";
    public static final String OWN_PROFILE_KEY_BY_FOLLOWED_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).ownProfileKey(#followedUserId)";
    public static final String PUBLIC_PROFILE_KEY =
            "T(com.app.socialservice.user.application.cache.CacheNames).publicProfileKey(#requesterUserId, #targetUserId)";

    private CacheNames() {
    }

    public static String ownProfileKey(UUID userId) {
        validateUserId(userId, "userId");
        return userId.toString();
    }

    public static String ownProfileKey(UpdateOwnUserProfileCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return ownProfileKey(command.userId());
    }

    public static String publicProfileKey(UUID requesterUserId, UUID targetUserId) {
        validateUserId(requesterUserId, "requesterUserId");
        validateUserId(targetUserId, "targetUserId");
        return String.format("%s:%s", requesterUserId, targetUserId);
    }

    private static void validateUserId(UUID userId, String fieldName) {
        if (userId == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
