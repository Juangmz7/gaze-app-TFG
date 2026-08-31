package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    @Query("""
            SELECT collabMember.id.userId
            FROM CollabMemberEntity collabMember
            WHERE collabMember.id.collabId = :collabId
            """)
    List<UUID> findUserIdsByCollabId(@Param("collabId") UUID collabId);

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
}
