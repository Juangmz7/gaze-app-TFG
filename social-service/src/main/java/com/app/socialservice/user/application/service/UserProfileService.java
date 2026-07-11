package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserStatsService userStatsService;

    @Cacheable(cacheNames = CacheNames.OWN_PROFILE, key = CacheNames.OWN_PROFILE_KEY_BY_USER_ID)
    @Transactional(readOnly = true)
    public OwnUserProfileResponse getOwnProfile(UUID userId) {
        validateUserId(userId);
        log.info("Retrieving own profile for user {}", userId);

        var response = loadOwnProfileResponse(userId);

        log.info("Own profile retrieved for user {}", userId);
        return response;
    }

    @Caching(
            put = @CachePut(
                    cacheNames = CacheNames.OWN_PROFILE,
                    key = CacheNames.OWN_PROFILE_KEY_BY_COMMAND
            ),
            evict = @CacheEvict(cacheNames = CacheNames.PUBLIC_PROFILE, allEntries = true)
    )
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

        log.info("Updated profile for user {}", command.userId());

        return getOwnProfile(command.userId());
    }

    @Cacheable(
            cacheNames = CacheNames.PUBLIC_PROFILE,
            key = CacheNames.PUBLIC_PROFILE_KEY
    )
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(UUID requesterUserId, UUID targetUserId) {
        validateUserIds(requesterUserId, targetUserId);

        if (requesterUserId.equals(targetUserId)) {
            throw new SelfProfileRequestNotAllowedException("A user cannot request their own public profile");
        }

        var profileDetails = userRepository.findProfileDetails(requesterUserId, targetUserId)
                .orElseThrow(() -> new UserProfileNotFoundException(targetUserId));

        if (profileDetails.blocked()) {
            throw new UserProfileBlockedException(String.format(
                    "Profile access is blocked between %s and %s",
                    requesterUserId,
                    targetUserId
            ));
        }

        log.info("Retrieving user {} profile requested from user {}", targetUserId, requesterUserId);

        return new UserProfileResponse(
                profileDetails.userId(),
                profileDetails.username(),
                profileDetails.description(),
                profileDetails.socialMedia(),
                userStatsService.getFollowersCount(profileDetails.userId()),
                userStatsService.getFollowingCount(profileDetails.userId()),
                userStatsService.getPostCount(profileDetails.userId()),
                profileDetails.profilePic(),
                profileDetails.following(),
                profileDetails.followsMe(),
                profileDetails.banned()
        );
    }
    
    private Optional<User> getUserById(UUID id) {
        return userRepository.findById(id);
    }

    private OwnUserProfileResponse loadOwnProfileResponse(UUID userId) {
        var userProfile = userRepository.findOwnProfileById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return new OwnUserProfileResponse(
                userId,
                userProfile.username(),
                userProfile.description(),
                userProfile.socialMedia(),
                userStatsService.getFollowersCount(userId),
                userStatsService.getFollowingCount(userId),
                userStatsService.getPostCount(userId),
                userProfile.profilePic(),
                userProfile.banned()
        );
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
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.userId() == null) {
            throw new IllegalArgumentException("command.userId must not be null");
        }
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

    private void validateUserIds(UUID requesterUserId, UUID targetUserId) {
        if (requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId must not be null");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId must not be null");
        }
    }

    private void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
