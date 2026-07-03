package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.UserProfileDetails;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import com.app.socialservice.user.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.application.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private UserProfileService userProfileService;

    @Test
    void shouldThrowForbiddenWhenRequesterHasBlockedTarget() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(userRepository.findProfileDetails(requesterUserId, targetUserId))
                .thenReturn(Optional.of(buildProfileDetails(targetUserId, true, false)));

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileBlockedException.class)
                .hasMessageContaining("Profile access is blocked");

        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldThrowForbiddenWhenTargetHasBlockedRequester() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(userRepository.findProfileDetails(requesterUserId, targetUserId))
                .thenReturn(Optional.of(buildProfileDetails(targetUserId, true, true)));

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileBlockedException.class)
                .hasMessageContaining("Profile access is blocked");

        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldThrowNotFoundWhenTargetUserDoesNotExist() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        when(userRepository.findProfileDetails(requesterUserId, targetUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, targetUserId))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessageContaining(targetUserId.toString());

        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldThrowBadRequestWhenRequesterRequestsOwnProfile() {
        var requesterUserId = UUID.randomUUID();

        assertThatThrownBy(() -> userProfileService.getUserProfile(requesterUserId, requesterUserId))
                .isInstanceOf(SelfProfileRequestNotAllowedException.class)
                .hasMessageContaining("cannot request their own public profile");

        verifyNoInteractions(userRepository);
        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldReturnProfileDataWithCorrectCountersAndFollowingStatus() {
        var requesterUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var socialMedia = Map.of("github", "target-user", "linkedin", "target-user-linkedin");

        when(userRepository.findProfileDetails(requesterUserId, targetUserId))
                .thenReturn(Optional.of(new UserProfileDetails(
                        targetUserId,
                        "target-user",
                        "Target description",
                        socialMedia,
                        "https://cdn.example.com/profile.png",
                        true,
                        false,
                        false
                )));
        when(userStatsService.getFollowersCount(targetUserId)).thenReturn(17L);
        when(userStatsService.getFollowingCount(targetUserId)).thenReturn(9L);
        when(userStatsService.getPostCount(targetUserId)).thenReturn(23L);

        var response = userProfileService.getUserProfile(requesterUserId, targetUserId);

        assertThat(response.username()).isEqualTo("target-user");
        assertThat(response.description()).isEqualTo("Target description");
        assertThat(response.socialMedia()).containsAllEntriesOf(socialMedia);
        assertThat(response.followerCount()).isEqualTo(17L);
        assertThat(response.followingCount()).isEqualTo(9L);
        assertThat(response.postCount()).isEqualTo(23L);
        assertThat(response.profilePic()).isEqualTo("https://cdn.example.com/profile.png");
        assertThat(response.following()).isTrue();
        assertThat(response.isBanned()).isFalse();
    }

    @Test
    void shouldReturnOwnProfileUsingRedisCountersAndPostgresFields() {
        var userId = UUID.randomUUID();
        var ownProfileData = new OwnUserProfileData(
                "profile-user",
                "Persisted bio",
                java.util.Map.of("github", "profile-user"),
                7L,
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(ownProfileData));
        when(userStatsService.getFollowersCount(userId)).thenReturn(11L);
        when(userStatsService.getFollowingCount(userId)).thenReturn(13L);

        var response = userProfileService.getOwnProfile(userId);

        assertThat(response.username()).isEqualTo("profile-user");
        assertThat(response.description()).isEqualTo("Persisted bio");
        assertThat(response.socialMedia()).containsEntry("github", "profile-user");
        assertThat(response.followersCount()).isEqualTo(11L);
        assertThat(response.followingCount()).isEqualTo(13L);
        assertThat(response.postCount()).isEqualTo(7L);
        assertThat(response.profilePicture()).isEqualTo("https://example.com/avatar.png");
        assertThat(response.isBanned()).isTrue();
    }

    @Test
    void shouldThrowWhenOwnProfileUserDoesNotExist() {
        var userId = UUID.randomUUID();

        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getOwnProfile(userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + userId);

        verifyNoInteractions(userStatsService);
    }

    @Test
    void shouldRejectNullUserIdWhenGettingOwnProfile() {
        assertThatThrownBy(() -> userProfileService.getOwnProfile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId must not be null");

        verifyNoInteractions(userRepository, userStatsService);
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

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.updateProfile(any(User.class))).thenReturn(savedUser);

        var response = userProfileService.updateOwnUserProfile(command);

        verify(userRepository).updateProfile(existingUser);
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "profile-user",
                "New bio",
                Map.of(
                        "github", "new-user",
                        "linkedin", "profile-user"
                ),
                0L,
                0L,
                0L,
                "https://cdn.example.com/new.png",
                false
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

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));

        var response = userProfileService.updateOwnUserProfile(command);

        verify(userRepository, never()).updateProfile(any(User.class));
        assertThat(response).isEqualTo(new OwnUserProfileResponse(
                userId,
                "same-user",
                "Same bio",
                Map.of("github", "same-user"),
                0L,
                0L,
                0L,
                "https://cdn.example.com/same.png",
                false
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

        assertThatThrownBy(() -> userProfileService.updateOwnUserProfile(overlongProfilePictureCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command.profilePicture must not exceed 255 characters");

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

    private UserProfileDetails buildProfileDetails(UUID userId, boolean blocked, boolean banned) {
        return new UserProfileDetails(
                userId,
                "blocked-user",
                "Blocked description",
                Map.of("github", "blocked-user"),
                "https://cdn.example.com/blocked.png",
                false,
                blocked,
                banned
        );
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
