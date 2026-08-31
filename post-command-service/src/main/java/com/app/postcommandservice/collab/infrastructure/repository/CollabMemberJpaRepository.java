package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    @Modifying
    @Query(value = "UPDATE collab_members SET collab_member_status = 'LEFT' "
            + "WHERE collab_id = :collabId AND user_id = :userId AND collab_member_status = 'ACCEPTED'",
            nativeQuery = true)
    int leaveIfAccepted(@Param("collabId") UUID collabId, @Param("userId") UUID userId);
}
