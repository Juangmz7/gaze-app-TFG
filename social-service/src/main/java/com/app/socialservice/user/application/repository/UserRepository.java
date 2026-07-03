package com.app.socialservice.user.application.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.dto.RecommendedUserDetails;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.application.dto.OwnUserProfileData;

public interface UserRepository {
    User updateProfile(User user);
    Optional<User> findById(UUID id);
    Optional<OwnUserProfileData> findOwnProfileById(UUID id);
    boolean existsById(UUID id);
    List<RecommendedUserDetails> findRecommendedUsersByIds(List<UUID> userIds, UUID requesterUserId);
    boolean insertIfAbsent(User user);
    boolean updateAuthInfo(UUID id, String username, String email);
    boolean deleteAndObfuscate(UUID id);
}
