package com.app.socialservice.block.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaBlockRepository extends JpaRepository<BlockEntity, BlockEntityId> {

    @Query(
            value = """
                    SELECT CASE
                        WHEN blocker_id = :userId THEN blocked_id
                        ELSE blocker_id
                    END
                    FROM blocks
                    WHERE blocker_id = :userId OR blocked_id = :userId
                    """,
            nativeQuery = true
    )
    List<UUID> findBlockedUserIds(UUID userId);

    @Query(
            value = """
                    SELECT blocked_id
                    FROM blocks
                    WHERE blocker_id = :blockerUserId
                    ORDER BY created_at DESC, blocked_id ASC
                    LIMIT :limit OFFSET :offset
                    """,
            nativeQuery = true
    )
    List<UUID> findBlockedUserIdsByBlockerId(UUID blockerUserId, int limit, long offset);

    @Modifying
    @Query(
            value = """
                    INSERT INTO blocks (blocker_id, blocked_id, created_at)
                    VALUES (:blockerUserId, :blockedUserId, :createdAt)
                    ON CONFLICT (blocker_id, blocked_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(UUID blockerUserId, UUID blockedUserId, Instant createdAt);

    @Modifying
    @Query(
            value = """
                    DELETE FROM blocks
                    WHERE blocker_id = :blockerUserId AND blocked_id = :blockedUserId
                    """,
            nativeQuery = true
    )
    int deleteByUsers(UUID blockerUserId, UUID blockedUserId);
}
