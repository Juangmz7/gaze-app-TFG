package com.app.postcommandservice.commentlike.infrastructure.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.repository.CommentJpaRepository;
import com.app.postcommandservice.commentlike.application.repository.CommentLikeValidationRepository;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;

@Repository
@RequiredArgsConstructor
public class CommentLikeValidationRepositoryImpl implements CommentLikeValidationRepository {

    private final CommentJpaRepository commentJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public Optional<ActiveComment> findActiveComment(UUID postId, UUID commentId) {
        return commentJpaRepository.findOwnerIdByIdAndPostIdAndStatus(commentId, postId, CommentStatus.ACTIVE)
                .map(ownerUserId -> new ActiveComment(postId, commentId, ownerUserId));
    }

    @Override
    public boolean existsBlockRelationship(UUID likerUserId, UUID commentOwnerId) {
        return !blockReadModelJpaRepository.findBlockedUserIdsBetween(likerUserId, Set.of(commentOwnerId)).isEmpty();
    }
}
