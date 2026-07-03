package com.app.socialservice.user.application.service;

import java.util.UUID;

import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.SelfProfileRequestNotAllowedException;
import com.app.socialservice.user.domain.exception.UserProfileBlockedException;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserStatsService userStatsService;

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

        return new UserProfileResponse(
                profileDetails.username(),
                profileDetails.description(),
                profileDetails.socialMedia(),
                userStatsService.getFollowersCount(profileDetails.userId()),
                userStatsService.getFollowingCount(profileDetails.userId()),
                userStatsService.getPostCount(profileDetails.userId()),
                profileDetails.profilePic(),
                profileDetails.following(),
                profileDetails.banned()
        );
    }

    private void validateUserIds(UUID requesterUserId, UUID targetUserId) {
        if (requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId must not be null");
        }
        if (targetUserId == null) {
            throw new IllegalArgumentException("targetUserId must not be null");
        }
    }
}
