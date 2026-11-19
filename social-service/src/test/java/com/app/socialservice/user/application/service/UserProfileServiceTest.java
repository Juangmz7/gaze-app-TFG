package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.ProfileRelationship;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.port.ProfilePictureStorage;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureException;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureUrlException;
import com.app.socialservice.user.domain.exception.InvalidUsernameLookupException;
import com.app.socialservice.user.domain.exception.ProfilePictureNotUploadedException;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserBannedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.domain.model.valueobj.UsernameLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStatsService userStatsService;

    @Mock
    private PublicUserProfileReader publicUserProfileReader;

    @Mock
    private UserCacheEvictor userCacheEvictor;

    @Mock
    private ProfilePictureStorage profilePictureStorage;

    private UserProfileService userProfileService;

    // A real reader over the mocks: the own profile tests read through it
    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileService(
                userRepository,
                publicUserProfileReader,
                new OwnUserProfileReader(userRepository, userStatsService),
                userCacheEvictor,
                profilePictureStorage
        );
    }

    @Nested
    class CacheAnnotationsTest {

        @Test
        void shouldCacheTheOwnProfileInTheReaderAndCheckTheAccountStatusOnEveryRequest() throws NoSuchMethodException {
            var cacheable = OwnUserProfileReader.class.getMethod("read", UUID.class).getAnnotation(Cacheable.class);

            assertThat(cacheable.cacheNames()).containsExactly(CacheNames.OWN_PROFILE);
            assertThat(cacheable.key()).isEqualTo(CacheNames.KEY_BY_USER_ID);
            // A ban must apply at once, so the status check itself is not cached
            assertThat(UserProfileService.class.getMethod("getOwnProfile", UUID.class).getAnnotation(Cacheable.class))
                    .isNull();
        }

        @Test
        void shouldNotCacheThePublicProfileResponseBecauseItDependsOnTheRequester() throws NoSuchMethodException {
            // Its requester-independent part is cached by PublicUserProfileReader
            var method = UserProfileService.class.getMethod("getUserProfile", UUID.class, UUID.class);

            assertThat(method.getAnnotation(Cacheable.class)).isNull();
        }

        @Test
        void shouldReplaceTheOwnProfileOnProfileUpdate() throws NoSuchMethodException {
            var method = UserProfileService.class.getMethod("updateOwnUserProfile", UpdateOwnUserProfileCommand.class);
            var cachePut = method.getAnnotation(CachePut.class);

            assertThat(cachePut).isNotNull();
            assertThat(cachePut.cacheNames()).containsExactly(CacheNames.OWN_PROFILE);
            assertThat(cachePut.key()).isEqualTo(CacheNames.KEY_BY_COMMAND);
            assertThat(method.getAnnotation(Caching.class)).isNull();
        }
    }

    @Test
    void shouldThrowForbiddenWhenEitherUserBlockedTheOther() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(publicUserProfileReader.read(targetUserId)).thenReturn(publicProfile(targetUserId));
        when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                .thenReturn(new ProfileRelationship(false, false, true, true));

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileBlockedException.class)
                .hasMessageContaining("Profile access is blocked");
    }

    @Test
    void shouldThrowNotFoundWhenTheUserWasBannedOrDeletedAfterTheProfileWasCached() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(publicUserProfileReader.read(targetUserId)).thenReturn(publicProfile(targetUserId));
        when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                .thenReturn(new ProfileRelationship(false, false, false, false));

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessageContaining(targetUserId.toString());
    }

    @Test
    void shouldThrowNotFoundWhenTheReaderFindsNoVisibleProfile() {
        // Missing, deleted or banned: the reader throws and nothing is cached
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(publicUserProfileReader.read(targetUserId)).thenThrow(new UserProfileNotFoundException(targetUserId));

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessageContaining(targetUserId.toString());

        verify(userRepository, never()).findProfileRelationship(any(), any());
    }

    @Test
    void shouldThrowBadRequestWhenRequesterRequestsOwnProfile() {
        var requesterUserId = UUID.randomUUID();

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, requesterUserId))
                .isInstanceOf(SelfProfileRequestNotAllowedException.class)
                .hasMessageContaining("cannot request their own public profile");

        verifyNoInteractions(userRepository, publicUserProfileReader);
    }

    @Test
    void shouldCombineTheCachedProfileWithTheRelationshipOfTheRequester() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var socialMedia = Map.of("github", "target-user", "linkedin", "target-user-linkedin");

        when(publicUserProfileReader.read(targetUserId)).thenReturn(new PublicUserProfile(
                targetUserId,
                "target-user",
                "Target description",
                socialMedia,
                "https://cdn.example.com/profile.png",
                17L,
                9L,
                23L
        ));
        when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                .thenReturn(new ProfileRelationship(true, false, false, true));

        var response = userProfileService.getUserProfile(requesterUserId, targetUserId);

        assertThat(response.username()).isEqualTo("target-user");
        assertThat(response.id()).isEqualTo(targetUserId);
        assertThat(response.description()).isEqualTo("Target description");
        assertThat(response.socialMedia()).containsAllEntriesOf(socialMedia);
        assertThat(response.followerCount()).isEqualTo(17L);
        assertThat(response.followingCount()).isEqualTo(9L);
        assertThat(response.postCount()).isEqualTo(23L);
        assertThat(response.profilePic()).isEqualTo("https://cdn.example.com/profile.png");
        assertThat(response.following()).isTrue();
        assertThat(response.followsMe()).isFalse();
    }

    @Test
    void shouldReturnOwnProfileWithItsStats() {
        var userId = UUID.randomUUID();
        var ownProfileData = new OwnUserProfileData(
                "profile-user",
                "Persisted bio",
                java.util.Map.of("github", "profile-user"),
                7L,
                "https://example.com/avatar.png",
                false
        );

        when(userRepository.findAccountStatus(userId)).thenReturn(Optional.of(UserAccountStatus.ACCEPTED));
        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(ownProfileData));
        when(userStatsService.getStats(userId)).thenReturn(new UserStats(11L, 13L, 19L));

        var response = userProfileService.getOwnProfile(userId);

        assertThat(response.username()).isEqualTo("profile-user");
        assertThat(response.description()).isEqualTo("Persisted bio");
        assertThat(response.socialMedia()).containsEntry("github", "profile-user");
        assertThat(response.followersCount()).isEqualTo(11L);
        assertThat(response.followingCount()).isEqualTo(13L);
        assertThat(response.postCount()).isEqualTo(19L);
        assertThat(response.profilePicture()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void shouldThrowForbiddenWhenOwnProfileUserIsBanned() {
        var userId = UUID.randomUUID();
        // Live: also when the profile was cached before the ban
        when(userRepository.findAccountStatus(userId)).thenReturn(Optional.of(UserAccountStatus.BANNED));

        assertThatThrownBy(() -> userProfileService.getOwnProfile(userId))
                .isInstanceOf(UserBannedException.class)
                .hasMessage("User is banned: " + userId);

        verify(userRepository, never()).findOwnProfileById(any());
        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldThrowWhenOwnProfileUserDoesNotExist() {
        var userId = UUID.randomUUID();

        when(userRepository.findAccountStatus(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getOwnProfile(userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + userId);

        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldUpdateOwnProfileWhenDataChanges() {
        var userId = UUID.randomUUID();
        var existingUser = buildUser(userId, "profile-user", "profile@example.com", UserAccountStatus.ACCEPTED);
        existingUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/old.png"));
        existingUser.setBio(new UserBio("Old bio", Map.of("github", "old-user")));
        var command = new UpdateOwnUserProfileCommand(
                userId,
                "New bio",
                "https://cdn.example.com/new.png",
                Map.of("github", "new-user", "linkedin", "profile-user")
        );
        var savedUser = buildUser(userId, "profile-user", "profile@example.com", UserAccountStatus.ACCEPTED);
        savedUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/new.png"));
        savedUser.setBio(new UserBio("New bio", Map.of(
                "github", "new-user",
                "linkedin", "profile-user"
        )));
        var ownProfileData = new OwnUserProfileData(
                "profile-user",
                "New bio",
                Map.of(
                        "github", "new-user",
                        "linkedin", "profile-user"
                ),
                14L,
                "https://cdn.example.com/new.png",
                false
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.updateProfile(any(User.class))).thenReturn(savedUser);
        when(userRepository.findAccountStatus(userId)).thenReturn(Optional.of(UserAccountStatus.ACCEPTED));
        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(ownProfileData));
        when(userStatsService.getStats(userId)).thenReturn(new UserStats(22L, 31L, 41L));

        var response = userProfileService.updateOwnUserProfile(command);

        verify(userRepository).updateProfile(existingUser);
        // Both go before the profile is read back, or the cached old own profile would be returned
        verify(userCacheEvictor).evictProfiles(userId);
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "profile-user",
                "New bio",
                Map.of(
                        "github", "new-user",
                        "linkedin", "profile-user"
                ),
                22L,
                31L,
                41L,
                "https://cdn.example.com/new.png"
        ));
    }

    @Test
    void shouldNotUpdateOwnProfileWhenDataIsUnchanged() {
        var userId = UUID.randomUUID();
        var existingUser = buildUser(userId, "same-user", "same@example.com", UserAccountStatus.ACCEPTED);
        existingUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/same.png"));
        existingUser.setBio(new UserBio("Same bio", Map.of("github", "same-user")));
        var command = new UpdateOwnUserProfileCommand(
                userId,
                "Same bio",
                "https://cdn.example.com/same.png",
                Map.of("github", "same-user")
        );
        var ownProfileData = new OwnUserProfileData(
                "same-user",
                "Same bio",
                Map.of("github", "same-user"),
                5L,
                "https://cdn.example.com/same.png",
                false
        );

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.findAccountStatus(userId)).thenReturn(Optional.of(UserAccountStatus.ACCEPTED));
        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(ownProfileData));
        when(userStatsService.getStats(userId)).thenReturn(new UserStats(8L, 13L, 21L));

        var response = userProfileService.updateOwnUserProfile(command);

        verify(userRepository, never()).updateProfile(any(User.class));
        verifyNoInteractions(userCacheEvictor);
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "same-user",
                "Same bio",
                Map.of("github", "same-user"),
                8L,
                13L,
                21L,
                "https://cdn.example.com/same.png"
        ));
    }

    @Test
    void shouldThrowValidationErrorForInvalidOwnProfileInputs() {
        var blankDescriptionCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "   ",
                null,
                Map.of()
        );
        var overlongDescriptionCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "a".repeat(UserBio.MAX_DESCRIPTION_LENGTH + 1),
                null,
                Map.of()
        );
        var overlongProfilePictureCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "Valid bio",
                "https://%s".formatted("a".repeat(ProfilePictureUrl.MAX_LENGTH)),
                Map.of("github", "valid-user")
        );
        var invalidUrlCommand = new UpdateOwnUserProfileCommand(
                UUID.randomUUID(),
                "Valid bio",
                "ftp://cdn.example.com/profile.png",
                Map.of("github", "valid-user")
        );

        assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(blankDescriptionCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.description must not be blank");

        assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(overlongDescriptionCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.description must not exceed 255 characters");

        when(userRepository.findById(overlongProfilePictureCommand.userId()))
                .thenReturn(Optional.of(buildUser(
                        overlongProfilePictureCommand.userId(),
                        "valid-user",
                        "valid@example.com",
                        UserAccountStatus.ACCEPTED
                )));
        assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(overlongProfilePictureCommand))
                .isInstanceOf(InvalidProfilePictureUrlException.class)
                .hasMessage("Profile picture URL must not exceed 255 characters");

        when(userRepository.findById(invalidUrlCommand.userId()))
                .thenReturn(Optional.of(buildUser(
                        invalidUrlCommand.userId(),
                        "valid-user",
                        "valid@example.com",
                        UserAccountStatus.ACCEPTED
                )));

        assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(invalidUrlCommand))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Profile picture URL");

        verify(userRepository, never()).updateProfile(any(User.class));
    }

    private PublicUserProfile publicProfile(UUID userId) {
        return new PublicUserProfile(
                userId,
                "blocked-user",
                "Blocked description",
                Map.of("github", "blocked-user"),
                "https://cdn.example.com/blocked.png",
                0L,
                0L,
                0L
        );
    }

    @Nested
    class ProfileByUsernameTest {

        private final UUID requesterUserId = UUID.fromString("3f2b8c1e-7d4a-4b6e-9c2f-1a5d8e7b3c90");
        private final UUID targetUserId = UUID.fromString("8d1e4f7a-2b5c-4e9d-a3f6-7c0b1d2e4f58");

        @Test
        void shouldLookUpTheUsernameCaseInsensitivelyAndReturnTheSameProfileAsById() {
            when(userRepository.findUserIdByUsername(new UsernameLookup("target.user")))
                    .thenReturn(Optional.of(targetUserId));
            when(publicUserProfileReader.read(targetUserId)).thenReturn(publicProfile(targetUserId));
            when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                    .thenReturn(new ProfileRelationship(true, true, false, true));

            var byUsername = userProfileService.getUserProfileByUsername(requesterUserId, "Target.USER");
            var byId = userProfileService.getUserProfile(requesterUserId, targetUserId);

            assertThat(byUsername).isEqualTo(byId);
            assertThat(byUsername.id()).isEqualTo(targetUserId);
            assertThat(byUsername.following()).isTrue();
            assertThat(byUsername.followsMe()).isTrue();
            verify(userRepository).findUserIdByUsername(new UsernameLookup("target.user"));
        }

        @Test
        void shouldRejectAnInvalidUsernameWithoutQueryingTheRepository() {
            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "jane-doe"))
                    .isInstanceOf(InvalidUsernameLookupException.class);
            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "a".repeat(65)))
                    .isInstanceOf(InvalidUsernameLookupException.class);

            verifyNoInteractions(userRepository, publicUserProfileReader);
        }

        @Test
        void shouldThrowNotFoundForAnUnknownUsername() {
            when(userRepository.findUserIdByUsername(new UsernameLookup("ghost"))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "ghost"))
                    .isInstanceOf(UserProfileNotFoundException.class)
                    .hasMessageContaining("ghost");

            verifyNoInteractions(publicUserProfileReader);
        }

        @Test
        void shouldThrowNotFoundInsteadOfForbiddenWhenEitherUserBlockedTheOther() {
            when(userRepository.findUserIdByUsername(new UsernameLookup("target.user")))
                    .thenReturn(Optional.of(targetUserId));
            when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                    .thenReturn(new ProfileRelationship(false, false, true, true));

            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "target.user"))
                    .isInstanceOf(UserProfileNotFoundException.class)
                    .hasMessageContaining("target.user")
                    .hasMessageNotContaining(targetUserId.toString());

            verifyNoInteractions(publicUserProfileReader);
        }

        @Test
        void shouldThrowNotFoundWhenTheUserIsBannedOrDeleted() {
            when(userRepository.findUserIdByUsername(new UsernameLookup("target.user")))
                    .thenReturn(Optional.of(targetUserId));
            when(userRepository.findProfileRelationship(requesterUserId, targetUserId))
                    .thenReturn(new ProfileRelationship(false, false, false, false));

            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "target.user"))
                    .isInstanceOf(UserProfileNotFoundException.class)
                    .hasMessageContaining("target.user");

            verifyNoInteractions(publicUserProfileReader);
        }

        @Test
        void shouldThrowBadRequestWhenTheUsernameIsTheRequesters() {
            when(userRepository.findUserIdByUsername(new UsernameLookup("self.user")))
                    .thenReturn(Optional.of(requesterUserId));

            assertThatThrownBy(() -> userProfileService.getUserProfileByUsername(requesterUserId, "Self.User"))
                    .isInstanceOf(SelfProfileRequestNotAllowedException.class);

            verifyNoInteractions(publicUserProfileReader);
        }

        @Test
        void shouldNotCacheTheResponseBecauseItDependsOnTheRequester() throws NoSuchMethodException {
            var method = UserProfileService.class.getMethod("getUserProfileByUsername", UUID.class, String.class);

            assertThat(method.getAnnotation(Cacheable.class)).isNull();
        }
    }

    @Nested
    class ProfilePictureValidationTest {

        private static final String CONTAINER_URL = "https://account.blob.core.windows.net/post-media/";
        private static final String PICTURE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

        private final UUID userId = UUID.fromString("0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11");
        private final String ownBlob = "avatars/" + userId + "/" + PICTURE + ".jpg";
        private User existingUser;

        @BeforeEach
        void setUpUser() {
            existingUser = buildUser(userId, "avatar-user", "avatar@example.com", UserAccountStatus.ACCEPTED);
            existingUser.setPictureUrl(new ProfilePictureUrl("https://cdn.example.com/old.png"));
            when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
            when(profilePictureStorage.findContainerBlobName(any())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                return url.startsWith(CONTAINER_URL)
                        ? Optional.of(url.substring(CONTAINER_URL.length()).split("\\?", 2)[0])
                        : Optional.empty();
            });
        }

        @Test
        void shouldRejectAProfilePictureOutsideTheCallersFolder() {
            var otherUsersBlob = "avatars/" + UUID.randomUUID() + "/" + PICTURE + ".jpg";

            assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(
                    pictureCommand(CONTAINER_URL + otherUsersBlob)))
                    .isInstanceOf(InvalidProfilePictureException.class);
            assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(
                    pictureCommand(CONTAINER_URL + "post-image.jpg")))
                    .isInstanceOf(InvalidProfilePictureException.class);

            verify(profilePictureStorage, never()).exists(any());
            verify(userRepository, never()).updateProfile(any(User.class));
        }

        @Test
        void shouldRejectAnAvatarThatWasNeverUploaded() {
            when(profilePictureStorage.exists(new ProfilePictureBlobName(ownBlob))).thenReturn(false);

            assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(pictureCommand(CONTAINER_URL + ownBlob)))
                    .isInstanceOf(ProfilePictureNotUploadedException.class);

            verify(userRepository, never()).updateProfile(any(User.class));
        }

        @Test
        void shouldStoreTheBareUrlOfAnUploadedAvatarEvenWhenSentWithASas() {
            var blobName = new ProfilePictureBlobName(ownBlob);
            when(profilePictureStorage.exists(blobName)).thenReturn(true);
            when(profilePictureStorage.blobUrl(blobName)).thenReturn(CONTAINER_URL + ownBlob);
            when(userRepository.updateProfile(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
            givenOwnProfileReadable(CONTAINER_URL + ownBlob);

            userProfileService.updateOwnUserProfile(pictureCommand(CONTAINER_URL + ownBlob + "?sp=cw&sig=abc"));

            verify(userRepository).updateProfile(existingUser);
            assertThat(existingUser.getPictureUrl()).isEqualTo(new ProfilePictureUrl(CONTAINER_URL + ownBlob));
        }

        @Test
        void shouldKeepTheCurrentAvatarWithoutCallingStorageWhenTheAppSendsItBackSigned() {
            existingUser.setPictureUrl(new ProfilePictureUrl(CONTAINER_URL + ownBlob));
            givenOwnProfileReadable(CONTAINER_URL + ownBlob);

            userProfileService.updateOwnUserProfile(pictureCommand(CONTAINER_URL + ownBlob + "?sp=r&sig=abc"));

            verify(profilePictureStorage, never()).exists(any());
            verify(userRepository, never()).updateProfile(any(User.class));
        }

        private UpdateOwnUserProfileCommand pictureCommand(String profilePicture) {
            return new UpdateOwnUserProfileCommand(userId, "Persisted bio", profilePicture,
                    Map.of("github", "avatar-user"));
        }

        private void givenOwnProfileReadable(String pictureUrl) {
            when(userRepository.findAccountStatus(userId)).thenReturn(Optional.of(UserAccountStatus.ACCEPTED));
            when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(new OwnUserProfileData(
                    "avatar-user", "Persisted bio", Map.of("github", "avatar-user"), 0L, pictureUrl, false)));
            when(userStatsService.getStats(userId)).thenReturn(UserStats.EMPTY);
        }
    }

    private User buildUser(UUID userId, String username, String email, UserAccountStatus status) {
        var user = new User(
                new UserId(userId),
                new Username(username),
                new Email(email)
        );
        user.setAccountStatus(status);
        user.setBio(new UserBio("Persisted bio", Map.of("github", username)));
        return user;
    }
}
