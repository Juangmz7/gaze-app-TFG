package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserBannedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserStatsService userStatsService;
    private final PublicUserProfileReader publicUserProfileReader;
    private final UserCacheEvictor userCacheEvictor;

    /** Cached per user until a change of its profile or counters evicts it. A banned user throws, never cached. */
    @Cacheable(cacheNames = CacheNames.OWN_PROFILE, key = CacheNames.KEY_BY_USER_ID)
    @Transactional(readOnly = true)
    public OwnUserProfileResponse getOwnProfile(UUID userId) {
        log.info("Retrieving own profile for user {}", userId);

        var ownProfile = userRepository.findOwnProfileById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        ensureUserIsNotBanned(userId, ownProfile.banned());
        var response = toOwnProfileResponse(userId, ownProfile);

        log.info("Own profile retrieved for user {}", userId);
        return response;
    }

    @CachePut(cacheNames = CacheNames.OWN_PROFILE, key = CacheNames.KEY_BY_COMMAND)
    @Transactional
    public OwnUserProfileResponse updateOwnUserProfile(UpdateOwnUserProfileCommand command) {
        validateUpdateOwnProfileCommand(command);

        var user = getUserById(command.userId())
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        var requestedPictureUrl = toProfilePictureUrl(command.profilePicture());
        var requestedBio = toUserBio(command.description(), command.socialMedia());

        if (!user.hasProfileChanges(requestedPictureUrl, requestedBio)) {
            return getOwnProfile(command.userId());
        }

        user.updateProfile(requestedPictureUrl, requestedBio);

        userRepository.updateProfile(user);
        // The own profile is replaced by @CachePut with the result
        userCacheEvictor.evictPublicProfiles(command.userId());

        log.info("Updated profile for user {}", command.userId());

        return getOwnProfile(command.userId());
    }

    /**
     * The profile part is cached per user (PublicUserProfileReader); following, follows
     * me and blocked depend on the requester and are read on every request.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(UUID requesterUserId, UUID targetUserId) {

        if (requesterUserId.equals(targetUserId)) {
            throw new SelfProfileRequestNotAllowedException("A user cannot request their own public profile");
        }

        var profile = publicUserProfileReader.read(targetUserId);
        var relationship = userRepository.findProfileRelationship(requesterUserId, targetUserId);

        if (relationship.blocked()) {
            throw new UserProfileBlockedException(String.format(
                    "Profile access is blocked between %s and %s",
                    requesterUserId,
                    targetUserId
            ));
        }

        log.info("Retrieving user {} profile requested from user {}", targetUserId, requesterUserId);

        return new UserProfileResponse(
                profile.userId(),
                profile.username(),
                profile.description(),
                profile.socialMedia(),
                profile.followersCount(),
                profile.followingCount(),
                profile.postCount(),
                profile.profilePic(),
                relationship.following(),
                relationship.followsMe()
        );
    }

    private Optional<User> getUserById(UUID id) {
        return userRepository.findById(id);
    }

    private OwnUserProfileResponse toOwnProfileResponse(UUID userId, OwnUserProfileData userProfile) {
        var stats = userStatsService.getStats(userId);
        return new OwnUserProfileResponse(
                userId,
                userProfile.username(),
                userProfile.description(),
                userProfile.socialMedia(),
                stats.followersCount(),
                stats.followingCount(),
                stats.postCount(),
                userProfile.profilePic()
        );
    }

    private void ensureUserIsNotBanned(UUID userId, boolean banned) {
        if (banned) {
            throw new UserBannedException(userId);
        }
    }

    private ProfilePictureUrl toProfilePictureUrl(String profilePicture) {
        if (profilePicture == null) {
            return null;
        }
        return new ProfilePictureUrl(profilePicture);
    }

    private UserBio toUserBio(String description, Map<String, String> socialMedia) {
        var normalizedSocialMedia = socialMedia == null ? Map.<String, String>of() : Map.copyOf(socialMedia);
        if (description == null && normalizedSocialMedia.isEmpty()) {
            return null;
        }
        return new UserBio(description, normalizedSocialMedia);
    }

    private void validateUpdateOwnProfileCommand(UpdateOwnUserProfileCommand command) {
        if (command.description() != null && command.description().isBlank()) {
            throw new IllegalArgumentException("command.description must not be blank");
        }
        if (command.description() != null && command.description().length() > UserBio.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "command.description must not exceed %d characters".formatted(UserBio.MAX_DESCRIPTION_LENGTH)
            );
        }
        if (command.profilePicture() != null && command.profilePicture().length() > ProfilePictureUrl.MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "command.profilePicture must not exceed %d characters".formatted(ProfilePictureUrl.MAX_LENGTH)
            );
        }
        if (command.socialMedia() != null) {
            command.socialMedia().forEach((platform, handle) -> {
                if (platform == null || platform.isBlank()) {
                    throw new IllegalArgumentException("command.socialMedia keys must not be null or blank");
                }
                if (handle == null || handle.isBlank()) {
                    throw new IllegalArgumentException("command.socialMedia values must not be null or blank");
                }
            });
        }
    }


}
