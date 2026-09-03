package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    Optional<CollabMemberEntity> findByIdCollabIdAndIdUserId(java.util.UUID collabId, java.util.UUID userId);
}
