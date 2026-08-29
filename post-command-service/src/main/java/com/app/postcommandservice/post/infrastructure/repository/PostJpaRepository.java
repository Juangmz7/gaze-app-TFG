package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;

public interface PostJpaRepository extends JpaRepository<PostEntity, UUID> {

    Optional<PostEntity> findFirstByCollabId(UUID collabId);

    @Query("""
            SELECT post.userId
            FROM PostEntity post
            WHERE post.id = :postId
              AND post.status = :status
            """)
    Optional<UUID> findOwnerIdByIdAndStatus(@Param("postId") UUID postId, @Param("status") PostStatus status);
}
