package com.app.socialservice.user.infrastructure.repository;

import com.app.socialservice.user.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Optional;
import com.app.socialservice.user.domain.enums.UserAccountStatus;

@Repository
public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByIdAndAccountStatus(UUID id, UserAccountStatus accountStatus);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO users (id, username, email, account_status, created_at, updated_at, version) " +
           "VALUES (:id, :username, :email, :accountStatus, NOW(), NOW(), 0) " +
           "ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("username") String username, 
                       @Param("email") String email, @Param("accountStatus") String accountStatus);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET username = :username, email = :email, updated_at = NOW(), version = version + 1 " +
                   "WHERE id = :id AND account_status = 'ACCEPTED'", nativeQuery = true)
    int updateAuthInfo(@Param("id") UUID id, @Param("username") String username, @Param("email") String email);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET account_status = 'DELETED', username = CONCAT(username, '_deleted_', CAST(:id AS text)), email = CONCAT(email, '_deleted_', CAST(:id AS text)), updated_at = NOW(), version = version + 1 " +
                   "WHERE id = :id AND account_status = 'ACCEPTED'", nativeQuery = true)
    int deleteAndObfuscate(@Param("id") UUID id);
}
