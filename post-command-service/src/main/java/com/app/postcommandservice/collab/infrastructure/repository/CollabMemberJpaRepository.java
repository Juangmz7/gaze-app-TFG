package com.app.postcommandservice.collab.infrastructure.repository;

import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CollabMemberEntity member
               set member.collabMemberStatus = :acceptedStatus
             where member.id.collabId = :collabId
               and member.id.userId = :userId
               and member.collabMemberStatus = :pendingStatus
            """)
    int acceptPendingMember(
            @Param("collabId") UUID collabId,
            @Param("userId") UUID userId,
            @Param("pendingStatus") CollabMemberStatus pendingStatus,
            @Param("acceptedStatus") CollabMemberStatus acceptedStatus
    );
    Optional<CollabMemberEntity> findByIdCollabIdAndIdUserId(UUID collabId, UUID userId);

    @Query("""
            SELECT collabMember.id.userId
            FROM CollabMemberEntity collabMember
            WHERE collabMember.id.collabId = :collabId
            """)
    List<UUID> findUserIdsByCollabId(@Param("collabId") UUID collabId);
}
