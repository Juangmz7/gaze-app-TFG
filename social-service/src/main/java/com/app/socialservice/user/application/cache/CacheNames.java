package com.app.socialservice.user.application.cache;

import java.util.UUID;

import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;

/**
 * The Redis caches of the user module, every one keyed by a single user id, so that a
 * change evicts exactly the entries of the users it touched (UserCacheEvictor):
 * <ul>
 *   <li>{@link #OWN_PROFILE}: a user's own profile, by that user.</li>
 *   <li>{@link #PUBLIC_PROFILE}: the part of a profile that is the same whoever asks,
 *   by the profile's user. What depends on the requester (following, follows me,
 *   blocked) is read on every request.</li>
 *   <li>{@link #RECOMMENDED_USERS}: the users recommended to follow, by the requester.
 *   A short TTL covers the changes of the graph two hops away.</li>
 * </ul>
 */
public final class CacheNames {

    public static final String OWN_PROFILE = "user-own-profile";
    public static final String PUBLIC_PROFILE = "user-public-profile";
    public static final String RECOMMENDED_USERS = "user-recommended-users";

    public static final String KEY_BY_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).userKey(#userId)";
    public static final String KEY_BY_TARGET_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).userKey(#targetUserId)";
    public static final String KEY_BY_REQUESTER_USER_ID =
            "T(com.app.socialservice.user.application.cache.CacheNames).userKey(#requesterUserId)";
    public static final String KEY_BY_COMMAND =
            "T(com.app.socialservice.user.application.cache.CacheNames).userKey(#command)";

    private CacheNames() {
    }

    public static String userKey(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return userId.toString();
    }

    public static String userKey(UpdateOwnUserProfileCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return userKey(command.userId());
    }
}
