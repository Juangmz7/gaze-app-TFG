package com.app.socialservice.user.application.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import com.app.socialservice.block.application.dto.BlockedUserDetails;
import com.app.socialservice.user.application.dto.ProfileRelationship;
import com.app.socialservice.user.application.dto.PublicUserProfileData;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.application.dto.RecommendedUserDetails;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.application.dto.OwnUserProfileData;

public interface UserRepository {
    User updateProfile(User user);
    Optional<User> findById(UUID id);
    Optional<PublicUserProfileData> findPublicProfileData(UUID targetUserId);
    ProfileRelationship findProfileRelationship(UUID requesterUserId, UUID targetUserId);
    Optional<UserAccountStatus> findAccountStatus(UUID id);
    Optional<OwnUserProfileData> findOwnProfileById(UUID id);
    boolean existsById(UUID id);
    List<BlockedUserDetails> findBlockedUsersByIds(List<UUID> userIds);
    List<RecommendedUserDetails> findRecommendedUsersByIds(List<UUID> userIds, UUID requesterUserId);
    boolean insertIfAbsent(User user);
    boolean updateAuthInfo(UUID id, String username, String email);
    boolean deleteAndObfuscate(UUID id);
}
