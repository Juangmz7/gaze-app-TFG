package com.app.socialservice.user.infrastructure.repository;

import com.app.socialservice.user.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;
import com.app.socialservice.user.domain.enums.UserAccountStatus;

@Repository
public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByIdAndAccountStatus(UUID id, UserAccountStatus accountStatus);
}
