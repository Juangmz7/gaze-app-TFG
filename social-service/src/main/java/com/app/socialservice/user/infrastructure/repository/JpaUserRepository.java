package com.app.socialservice.user.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

    interface RecommendedUserProjection {
        UUID getId();
        String getUsername();
        String getDescription();
        String getProfilePic();
        boolean getFollowsYou();
        Instant getCreatedAt();
    }

    Optional<UserEntity> findByIdAndAccountStatus(UUID id, UserAccountStatus accountStatus);

    @Query(
            value = """
                    SELECT u.id AS id,
                           u.username AS username,
                           u.description AS description,
                           u.picture_url AS "profilePic",
                           EXISTS (
                               SELECT 1
                               FROM follows f
                               WHERE f.follower_id = u.id
                                 AND f.followed_id = :requesterUserId
                                 AND f.status = 'ACTIVE'
                           ) AS "followsYou",
                           u.created_at AS "createdAt"
                    FROM users u
                    WHERE u.id IN (:userIds)
                      AND u.account_status = 'ACCEPTED'
                    """,
            nativeQuery = true
    )
    List<RecommendedUserProjection> findRecommendedUsersByIds(
            @Param("userIds") List<UUID> userIds,
            @Param("requesterUserId") UUID requesterUserId
    );

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
