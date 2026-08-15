package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.comment.application.repository.CommentRelationshipValidationRepository;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;

@Repository
@RequiredArgsConstructor
public class CommentRelationshipValidationRepositoryImpl implements CommentRelationshipValidationRepository {

    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public boolean existsBlockRelationship(UUID requesterUserId, UUID targetUserId) {
        return !blockReadModelJpaRepository.findBlockedUserIdsBetween(requesterUserId, Set.of(targetUserId)).isEmpty();
    }
}
