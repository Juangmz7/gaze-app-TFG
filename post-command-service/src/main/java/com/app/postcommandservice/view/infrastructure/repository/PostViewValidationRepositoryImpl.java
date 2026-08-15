package com.app.postcommandservice.view.infrastructure.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.view.application.repository.PostViewValidationRepository;

@Repository
@RequiredArgsConstructor
public class PostViewValidationRepositoryImpl implements PostViewValidationRepository {

    private final PostJpaRepository postJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public Optional<ActivePost> findActivePost(UUID postId) {
        return postJpaRepository.findOwnerIdByIdAndStatus(postId, PostStatus.ACTIVE)
                .map(ownerUserId -> new ActivePost(postId, ownerUserId));
    }

    @Override
    public boolean existsBlockRelationship(UUID viewerUserId, UUID creatorUserId) {
        return !blockReadModelJpaRepository.findBlockedUserIdsBetween(viewerUserId, Set.of(creatorUserId)).isEmpty();
    }
}
