package com.app.postcommandservice.like.infrastructure.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.like.application.repository.PostLikeValidationRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;

@Repository
@RequiredArgsConstructor
public class PostLikeValidationRepositoryImpl implements PostLikeValidationRepository {

    private final PostJpaRepository postJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public Optional<ActivePost> findActivePost(UUID postId) {
        return postJpaRepository.findOwnerIdByIdAndStatus(postId, PostStatus.ACTIVE)
                .map(ownerUserId -> new ActivePost(postId, ownerUserId));
    }

    @Override
    public boolean existsBlockRelationship(UUID likerUserId, UUID postOwnerId) {
        return !blockReadModelJpaRepository.findBlockedUserIdsBetween(likerUserId, Set.of(postOwnerId)).isEmpty();
    }
}
