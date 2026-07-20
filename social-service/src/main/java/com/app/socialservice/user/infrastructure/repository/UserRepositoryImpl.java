package com.app.socialservice.user.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import com.app.socialservice.block.application.dto.BlockedUserDetails;
import com.app.socialservice.user.application.dto.UserProfileDetails;
import com.app.socialservice.user.application.mapper.UserMapper;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.dto.RecommendedUserDetails;


import com.app.socialservice.user.infrastructure.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final JpaUserRepository jpaUserRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public User updateProfile(User user) {
        var entity = jpaUserRepository.findByIdAndAccountStatus(user.getId().value(), UserAccountStatus.ACCEPTED)
                .orElseThrow(() -> new UserNotFoundException(user.getId().value()));

        entity.setPictureUrl(user.getPictureUrl() == null ? null : user.getPictureUrl().value());
        entity.setBio(userMapper.toUserBioEmbeddable(user.getBio()));

        return userMapper.toDomain(entity);
    }

    @Override
    @Transactional
    public boolean updateAuthInfo(UUID id, String username, String email) {
        return jpaUserRepository.findByIdAndAccountStatus(id, UserAccountStatus.ACCEPTED)
                .map(entity -> {
                    entity.setUsername(username);
                    entity.setEmail(email);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean deleteAndObfuscate(UUID id) {
        return jpaUserRepository.findByIdAndAccountStatus(id, UserAccountStatus.ACCEPTED)
                .map(entity -> {
                    entity.setAccountStatus(UserAccountStatus.DELETED);
                    entity.setUsername(entity.getUsername() + "_deleted_" + id);
                    entity.setEmail(entity.getEmail() + "_deleted_" + id);
                    return true;
                })
                .orElse(false);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaUserRepository.findByIdAndAccountStatus(id, UserAccountStatus.ACCEPTED)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<UserProfileDetails> findProfileDetails(UUID requesterUserId, UUID targetUserId) {
        validateUserId(requesterUserId, "requesterUserId");
        validateUserId(targetUserId, "targetUserId");

        return jpaUserRepository.findProfileDetails(requesterUserId, targetUserId);
    }
  
    public Optional<OwnUserProfileData> findOwnProfileById(UUID id) {
        return jpaUserRepository.findByIdAndAccountStatusIn(
                        id,
                        List.of(UserAccountStatus.ACCEPTED, UserAccountStatus.BANNED)
                )
                .map(this::toOwnUserProfileData);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpaUserRepository.existsById(id);
    }

    @Override
    public List<BlockedUserDetails> findBlockedUsersByIds(List<UUID> userIds) {
        if (userIds == null) {
            throw new IllegalArgumentException("userIds must not be null");
        }
        if (userIds.isEmpty()) {
            return List.of();
        }

        return jpaUserRepository.findBlockedUsersByIds(userIds).stream()
                .map(projection -> new BlockedUserDetails(
                        projection.getUserId(),
                        projection.getUsername(),
                        projection.getProfilePic()
                ))
                .toList();
    }

    @Override
    public List<RecommendedUserDetails> findRecommendedUsersByIds(List<UUID> userIds, UUID requesterUserId) {
        if (userIds == null) {
            throw new IllegalArgumentException("userIds must not be null");
        }
        if (requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId must not be null");
        }
        if (userIds.isEmpty()) {
            return List.of();
        }

        return jpaUserRepository.findRecommendedUsersByIds(userIds, requesterUserId).stream()
                .map(projection -> new RecommendedUserDetails(
                        projection.getId(),
                        projection.getUsername(),
                        projection.getDescription(),
                        projection.getProfilePic(),
                        projection.getFollowsYou(),
                        projection.getCreatedAt()
                ))
                .toList();
    }

    @Override
    public boolean insertIfAbsent(User user) {
        var entity = userMapper.toEntity(user);
        int rows = jpaUserRepository.insertIfAbsent(
                entity.getId(),
                entity.getUsername(),
                entity.getEmail(),
                entity.getAccountStatus().name()
        );
        return rows > 0;
    }

    private void validateUserId(UUID userId, String fieldName) {
        if (userId == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
  
    private OwnUserProfileData toOwnUserProfileData(UserEntity userEntity) {
        var bio = userEntity.getBio();
        return new OwnUserProfileData(
                userEntity.getUsername(),
                bio == null ? null : bio.getDescription(),
                bio == null ? null : bio.getSocialMedia(),
                userEntity.getPostCount(),
                userEntity.getPictureUrl(),
                userEntity.getAccountStatus() == UserAccountStatus.BANNED
        );
    }
}
