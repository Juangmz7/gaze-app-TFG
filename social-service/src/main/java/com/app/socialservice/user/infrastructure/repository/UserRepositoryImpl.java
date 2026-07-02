package com.app.socialservice.user.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.mapper.UserMapper;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final JpaUserRepository jpaUserRepository;
    private final UserMapper userMapper;

    @Override
    public boolean updateAuthInfo(UUID id, String username, String email) {
        int rows = jpaUserRepository.updateAuthInfo(id, username, email);
        return rows > 0;
    }

    @Override
    public boolean deleteAndObfuscate(UUID id) {
        int rows = jpaUserRepository.deleteAndObfuscate(id);
        return rows > 0;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaUserRepository.findByIdAndAccountStatus(id, UserAccountStatus.ACCEPTED)
                .map(userMapper::toDomain);
    }

    @Override
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
    public boolean insertIfAbsent(User user) {
        UserEntity entity = userMapper.toEntity(user);
        int rows = jpaUserRepository.insertIfAbsent(
                entity.getId(),
                entity.getUsername(),
                entity.getEmail(),
                entity.getAccountStatus().name()
        );
        return rows > 0;
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
