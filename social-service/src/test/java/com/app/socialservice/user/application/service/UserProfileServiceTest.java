package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.dto.UserProfileDetails;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import com.app.socialservice.user.application.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
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
}
