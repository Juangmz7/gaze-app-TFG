package com.app.postcommandservice.post.application.repository;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface TaggedUserValidationRepository {

    Map<String, UUID> findUserIdsByUsernames(Set<String> usernames);

    Set<UUID> findBlockedUserIds(UUID userId, Set<UUID> taggedUserIds);
}
