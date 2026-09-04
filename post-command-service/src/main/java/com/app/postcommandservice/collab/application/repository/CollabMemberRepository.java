package com.app.postcommandservice.collab.application.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.CollabMember;

public interface CollabMemberRepository {

    CollabMember save(CollabMember collabMember);

    Optional<CollabMember> findByCollabIdAndUserId(UUID collabId, UUID userId);

    boolean acceptPendingMember(UUID collabId, UUID userId);

    Set<UUID> findUserIdsByCollabId(UUID collabId);

    boolean rejectPendingMember(UUID collabId, UUID userId);

    boolean deletePendingMember(UUID collabId, UUID userId);
}
