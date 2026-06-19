package com.app.socialservice.user.infrastructure.repository;

import com.app.socialservice.user.application.mapper.UserMapper;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final JpaUserRepository jpaUserRepository;
    private final UserMapper userMapper;

    @Override
    public User save(User user) {
        UserEntity entity = userMapper.toEntity(user);
        UserEntity savedEntity = jpaUserRepository.save(entity);
        return userMapper.toDomain(savedEntity);
    }


    @Override
    public Optional<User> findById(UUID id) {
        return jpaUserRepository.findByIdAndAccountStatus(id, UserAccountStatus.ACCEPTED)
                .map(userMapper::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpaUserRepository.existsById(id);
    }
}
