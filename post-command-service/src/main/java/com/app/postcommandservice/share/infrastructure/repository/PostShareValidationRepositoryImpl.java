package com.app.postcommandservice.share.infrastructure.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.share.application.repository.PostShareValidationRepository;

@Repository
@RequiredArgsConstructor
public class PostShareValidationRepositoryImpl implements PostShareValidationRepository {

    private final PostJpaRepository postJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public Optional<ShareablePost> findPost(UUID postId) {
        return postJpaRepository.findById(postId)
                .map(post -> new ShareablePost(post.getId(), post.getUserId(), post.getStatus()));
    }

    @Override
    public boolean existsBlockRelationship(UUID sharerUserId, UUID postOwnerId) {
        return !blockReadModelJpaRepository.findBlockedUserIdsBetween(sharerUserId, Set.of(postOwnerId)).isEmpty();
    }
}
