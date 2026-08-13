package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;

public interface BlockReadModelJpaRepository extends JpaRepository<BlockReadModelEntity, BlockReadModelId> {

    @Query("""
            SELECT block.id.blockedId
            FROM BlockReadModelEntity block
            WHERE block.id.blockerId = :userId
              AND block.id.blockedId IN :taggedUserIds
            UNION
            SELECT reverseBlock.id.blockerId
            FROM BlockReadModelEntity reverseBlock
            WHERE reverseBlock.id.blockedId = :userId
              AND reverseBlock.id.blockerId IN :taggedUserIds
            """)
    List<UUID> findBlockedUserIdsBetween(@Param("userId") UUID userId, @Param("taggedUserIds") Set<UUID> taggedUserIds);
}
