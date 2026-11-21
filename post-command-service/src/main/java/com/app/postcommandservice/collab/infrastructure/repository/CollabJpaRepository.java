package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;

public interface CollabJpaRepository extends JpaRepository<CollabEntity, UUID> {

    /** Hard-deletes a collab row (task 60: the collab of a never-accepted post with no other post). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM CollabEntity collab
            WHERE collab.id = :collabId
            """)
    int hardDeleteById(@Param("collabId") UUID collabId);
}
