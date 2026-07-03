package com.app.socialservice.user.application.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import com.app.socialservice.user.application.dto.UserProfileDetails;
import com.app.socialservice.user.application.dto.RecommendedUserDetails;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.application.dto.OwnUserProfileData;

public interface UserRepository {
    User updateProfile(User user);
    Optional<User> findById(UUID id);
    Optional<UserProfileDetails> findProfileDetails(UUID requesterUserId, UUID targetUserId);
    Optional<OwnUserProfileData> findOwnProfileById(UUID id);
    boolean existsById(UUID id);
    List<RecommendedUserDetails> findRecommendedUsersByIds(List<UUID> userIds, UUID requesterUserId);
    boolean insertIfAbsent(User user);
    boolean updateAuthInfo(UUID id, String username, String email);
    boolean deleteAndObfuscate(UUID id);
}
