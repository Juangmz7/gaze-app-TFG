package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.infrastructure.entity.CollabRequestIdempotencyEntity;

public interface CollabRequestIdempotencyJpaRepository extends JpaRepository<CollabRequestIdempotencyEntity, UUID> {

    /**
     * Removes the replay record whose entity is the given id: a post for "open collab and create
     * post", a collab for "open collab for existing post" (task 60; no FK).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM CollabRequestIdempotencyEntity record
            WHERE record.entityId = :entityId
            """)
    int deleteByEntityId(@Param("entityId") UUID entityId);
}
