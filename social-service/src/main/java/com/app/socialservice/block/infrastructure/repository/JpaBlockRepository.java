package com.app.socialservice.block.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaBlockRepository extends JpaRepository<BlockEntity, BlockEntityId> {

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
}
