package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    @Modifying
    @Query(value = "UPDATE collab_members SET collab_member_status = 'LEFT' "
            + "WHERE collab_id = :collabId AND user_id = :userId AND collab_member_status = 'ACCEPTED'",
            nativeQuery = true)
    int leaveIfAccepted(@Param("collabId") UUID collabId, @Param("userId") UUID userId);
    Optional<CollabMemberEntity> findByIdCollabIdAndIdUserId(UUID collabId, UUID userId);
  
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CollabMemberEntity member
               set member.collabMemberStatus = :rejectedStatus
             where member.id.collabId = :collabId
               and member.id.userId = :userId
               and member.collabMemberStatus = :pendingStatus
            """)
    int rejectPendingMember(
            @Param("collabId") UUID collabId,
            @Param("userId") UUID userId,
            @Param("pendingStatus") CollabMemberStatus pendingStatus,
            @Param("rejectedStatus") CollabMemberStatus rejectedStatus
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CollabMemberEntity member
               set member.collabMemberStatus = :deletedStatus
             where member.id.collabId = :collabId
               and member.id.userId = :userId
               and member.collabMemberStatus = :pendingStatus
            """)
    int deletePendingMember(
            @Param("collabId") UUID collabId,
            @Param("userId") UUID userId,
            @Param("pendingStatus") CollabMemberStatus pendingStatus,
            @Param("deletedStatus") CollabMemberStatus deletedStatus
    );
    Optional<CollabMemberEntity> findByIdCollabIdAndIdUserId(UUID collabId, UUID userId);

    @Query("""
            SELECT collabMember.id.userId
            FROM CollabMemberEntity collabMember
            WHERE collabMember.id.collabId = :collabId
            """)
    List<UUID> findUserIdsByCollabId(@Param("collabId") UUID collabId);
}
