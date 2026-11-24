package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.ProfileRelationship;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.port.ProfilePictureStorage;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.ProfilePictureNotUploadedException;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserBannedException;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UsernameLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PublicUserProfileReader publicUserProfileReader;
    private final OwnUserProfileReader ownUserProfileReader;
    private final UserCacheEvictor userCacheEvictor;
    private final ProfilePictureStorage profilePictureStorage;
    private final OwnUserProfileWriter ownUserProfileWriter;

    /**
     * The account status is checked live on every request, so a ban applies at once; the
     * profile itself comes from the cache (OwnUserProfileReader).
     */
    @Transactional(readOnly = true)
    public OwnUserProfileResponse getOwnProfile(UUID userId) {
        log.info("Retrieving own profile for user {}", userId);

        var status = userRepository.findAccountStatus(userId)
                .filter(accountStatus -> accountStatus != UserAccountStatus.DELETED)
                .orElseThrow(() -> new UserNotFoundException(userId));
        ensureUserIsNotBanned(userId, status == UserAccountStatus.BANNED);
        var response = ownUserProfileReader.read(userId);

        log.info("Own profile retrieved for user {}", userId);
        return response;
    }

    /**
     * Not one transaction on purpose: a new avatar is checked in Blob Storage (a network call)
     * between the read and the write, and no database connection must be held meanwhile. The
     * write and its UserUpdatedEvent outbox row commit in one transaction (OwnUserProfileWriter)
     * and, like before, the last full replacement wins.
     */
    @CachePut(cacheNames = CacheNames.OWN_PROFILE, key = CacheNames.KEY_BY_COMMAND)
    public OwnUserProfileResponse updateOwnUserProfile(UpdateOwnUserProfileCommand command) {
        validateUpdateOwnProfileCommand(command);

        var user = getUserById(command.userId())
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        var requestedPictureUrl = resolveRequestedPicture(user, command.profilePicture());
        var requestedBio = toUserBio(command.description(), command.socialMedia());

        if (!user.hasProfileChanges(requestedPictureUrl, requestedBio)) {
            return getOwnProfile(command.userId());
        }

        user.updateProfile(requestedPictureUrl, requestedBio);

        ownUserProfileWriter.updateProfile(user);
        // Evicted at once (no transaction is open here) so the read below does not return the
        // cached old profile; @CachePut then stores the fresh one
        userCacheEvictor.evictProfiles(command.userId());

        log.info("Updated profile for user {}", command.userId());

        return getOwnProfile(command.userId());
    }

    /**
     * The profile part is cached per user (PublicUserProfileReader); following, follows
     * me and blocked depend on the requester and are read on every request.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(UUID requesterUserId, UUID targetUserId) {
        ensureNotOwnProfile(requesterUserId, targetUserId);

        var profile = publicUserProfileReader.read(targetUserId);
        var relationship = userRepository.findProfileRelationship(requesterUserId, targetUserId);

        // Live, unlike the cached profile: deleted or banned since it was cached
        if (!relationship.targetVisible()) {
            throw new UserProfileNotFoundException(targetUserId);
        }
        if (relationship.blocked()) {
            throw new UserProfileBlockedException(String.format(
                    "Profile access is blocked between %s and %s",
                    requesterUserId,
                    targetUserId
            ));
        }

        log.info("Retrieving user {} profile requested from user {}", targetUserId, requesterUserId);

        return toUserProfileResponse(profile, relationship);
    }

    /**
     * The same profile as getUserProfile, found by username (mentions and tagged users carry
     * only the username), case-insensitively. Unknown, deleted, banned and blocked (either
     * direction) all answer the same 404, so a lookup by name never tells them apart. The
     * relationship is checked first, so a hidden profile is never read.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfileByUsername(UUID requesterUserId, String username) {
        var lookup = new UsernameLookup(username);
        var targetUserId = userRepository.findUserIdByUsername(lookup)
                .orElseThrow(() -> UserProfileNotFoundException.forUsername(lookup.value()));
        ensureNotOwnProfile(requesterUserId, targetUserId);

        var relationship = userRepository.findProfileRelationship(requesterUserId, targetUserId);
        if (!relationship.targetVisible() || relationship.blocked()) {
            log.info("Profile lookup of username {} by user {} hidden: visible={}, blocked={}",
                    lookup.value(), requesterUserId, relationship.targetVisible(), relationship.blocked());
            throw UserProfileNotFoundException.forUsername(lookup.value());
        }
        var profile = publicUserProfileReader.read(targetUserId);

        log.info("Retrieving user {} profile by username requested from user {}", targetUserId, requesterUserId);

        return toUserProfileResponse(profile, relationship);
    }

    private void ensureNotOwnProfile(UUID requesterUserId, UUID targetUserId) {
        if (requesterUserId.equals(targetUserId)) {
            throw new SelfProfileRequestNotAllowedException("A user cannot request their own public profile");
        }
    }

    private UserProfileResponse toUserProfileResponse(PublicUserProfile profile, ProfileRelationship relationship) {
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

    private void ensureUserIsNotBanned(UUID userId, boolean banned) {
        if (banned) {
            throw new UserBannedException(userId);
        }
    }

    /**
     * A URL of another host is kept as sent. A URL of the media container is reduced to its
     * bare blob URL (the app sends back the read-signed URL it got, and a SAS is never stored);
     * unless it is the current picture, it must be an uploaded avatar in the caller's folder.
     */
    private ProfilePictureUrl resolveRequestedPicture(User user, String profilePicture) {
        if (profilePicture == null) {
            return null;
        }
        var containerBlobName = profilePictureStorage.findContainerBlobName(profilePicture);
        if (containerBlobName.isEmpty()) {
            return new ProfilePictureUrl(profilePicture);
        }
        if (isCurrentPicture(user, containerBlobName.get())) {
            return user.getPictureUrl();
        }

        var blobName = ProfilePictureBlobName.ownedBy(user.getId(), containerBlobName.get());
        if (!profilePictureStorage.exists(blobName)) {
            log.warn("User {} set a profile picture that was never uploaded: blob={}",
                    user.getId().value(), blobName.value());
            throw new ProfilePictureNotUploadedException("The profile picture has not been uploaded yet");
        }
        return new ProfilePictureUrl(profilePictureStorage.blobUrl(blobName));
    }

    private boolean isCurrentPicture(User user, String requestedBlobName) {
        return user.getPictureUrl() != null && profilePictureStorage.findContainerBlobName(user.getPictureUrl().value())
                .filter(requestedBlobName::equals)
                .isPresent();
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
