package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.collab.application.repository.CollabJoinValidationRepository;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;

@Repository
@RequiredArgsConstructor
public class CollabJoinValidationRepositoryImpl implements CollabJoinValidationRepository {

    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public Set<UUID> findBlockedUserIds(UUID requesterUserId, Set<UUID> collabMemberUserIds) {
        if (collabMemberUserIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(blockReadModelJpaRepository.findBlockedUserIdsBetween(requesterUserId, collabMemberUserIds));
    }
}
