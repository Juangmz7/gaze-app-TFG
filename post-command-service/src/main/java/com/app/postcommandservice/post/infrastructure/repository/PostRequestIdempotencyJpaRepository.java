package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostRequestIdempotencyId;

public interface PostRequestIdempotencyJpaRepository
        extends JpaRepository<PostRequestIdempotencyEntity, PostRequestIdempotencyId> {

    /** Removes the create-request replay record of a hard-deleted post (task 60; no FK to posts). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM PostRequestIdempotencyEntity record
            WHERE record.postId = :postId
            """)
    int deleteByPostId(@Param("postId") UUID postId);
}
