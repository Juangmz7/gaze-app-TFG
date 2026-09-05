package com.app.postcommandservice.collab.application.repository;

import java.util.Set;
import java.util.UUID;

public interface CollabJoinValidationRepository {

    Set<UUID> findBlockedUserIds(UUID requesterUserId, Set<UUID> collabMemberUserIds);
}
