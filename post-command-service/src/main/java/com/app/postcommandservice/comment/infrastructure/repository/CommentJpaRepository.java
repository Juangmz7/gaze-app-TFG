package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;

public interface CommentJpaRepository extends JpaRepository<CommentEntity, UUID> {

    Optional<CommentEntity> findByIdAndPostId(UUID id, UUID postId);

    @Query("""
            select c.userId
            from CommentEntity c
            where c.id = :commentId
              and c.postId = :postId
              and c.status = :status
            """)
    Optional<UUID> findOwnerIdByIdAndPostIdAndStatus(
            @Param("commentId") UUID commentId,
            @Param("postId") UUID postId,
            @Param("status") CommentStatus status);
}
