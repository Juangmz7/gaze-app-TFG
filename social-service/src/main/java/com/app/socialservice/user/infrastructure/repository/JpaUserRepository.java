package com.app.socialservice.user.infrastructure.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.user.application.dto.UserProfileDetails;

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

    interface BlockedUserProjection {
        UUID getUserId();
        String getUsername();
        String getProfilePic();
    }

    Optional<UserEntity> findByIdAndAccountStatus(UUID id, UserAccountStatus accountStatus);

    @Query("""
            select new com.app.socialservice.user.application.dto.UserProfileDetails(
                user.id,
                user.username,
                user.bio.description,
                user.bio.socialMedia,
                user.pictureUrl,
                case when activeFollow.id.followerId is not null then true else false end,
                case when followsBack.id.followerId is not null then true else false end,
                case when requesterBlock.id.blockerId is not null or targetBlock.id.blockerId is not null
                    then true else false end,
                case when user.accountStatus = com.app.socialservice.user.domain.enums.UserAccountStatus.BANNED
                    then true else false end
            )
            from UserEntity user
            left join BlockEntity requesterBlock
                on requesterBlock.id.blockerId = :requesterUserId
                and requesterBlock.id.blockedId = :targetUserId
            left join BlockEntity targetBlock
                on targetBlock.id.blockerId = :targetUserId
                and targetBlock.id.blockedId = :requesterUserId
            left join FollowEntity activeFollow
                on activeFollow.id.followerId = :requesterUserId
                and activeFollow.id.followedId = :targetUserId
                and activeFollow.status = com.app.socialservice.follow.infrastructure.enums.FollowStatus.ACTIVE
            left join FollowEntity followsBack
                on followsBack.id.followerId = :targetUserId
                and followsBack.id.followedId = :requesterUserId
                and followsBack.status = com.app.socialservice.follow.infrastructure.enums.FollowStatus.ACTIVE
            where user.id = :targetUserId
                and user.accountStatus <> com.app.socialservice.user.domain.enums.UserAccountStatus.DELETED
            """)
    Optional<UserProfileDetails> findProfileDetails(
            @Param("requesterUserId") UUID requesterUserId,
            @Param("targetUserId") UUID targetUserId
    );
           
    Optional<UserEntity> findByIdAndAccountStatusIn(UUID id, Collection<UserAccountStatus> accountStatuses);
           
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

    @Query(
            value = """
                    SELECT u.id AS "userId",
                           u.username AS username,
                           u.picture_url AS "profilePic"
                    FROM users u
                    WHERE u.id IN (:userIds)
                    """,
            nativeQuery = true
    )
    List<BlockedUserProjection> findBlockedUsersByIds(@Param("userIds") List<UUID> userIds);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO users (id, username, email, account_status, created_at, updated_at, version) " +
           "VALUES (:id, :username, :email, :accountStatus, NOW(), NOW(), 0) " +
           "ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("username") String username, 
                       @Param("email") String email, @Param("accountStatus") String accountStatus);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE users 
            SET description = :description, 
                picture_url = :profilePicture,
                social_media = CAST(:socialMedia AS jsonb),
                updated_at = NOW(),
                version = COALESCE(version, 0) + 1
            WHERE id = :id AND account_status = 'ACCEPTED'
        """, nativeQuery = true)
    int updateProfileInfo(
            @Param("id") UUID id,
            @Param("description") String description,
            @Param("profilePicture") String profilePicture,
            @Param("socialMedia") String socialMedia
    );

}
