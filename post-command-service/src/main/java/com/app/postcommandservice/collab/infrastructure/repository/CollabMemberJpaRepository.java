package com.app.postcommandservice.collab.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;

public interface CollabMemberJpaRepository extends JpaRepository<CollabMemberEntity, CollabMemberId> {

    java.util.Optional<CollabMemberEntity> findByIdCollabIdAndIdUserId(java.util.UUID collabId, java.util.UUID userId);
}
