package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

public interface PostMediaJpaRepository extends JpaRepository<PostMediaEntity, UUID> {

    @Query("""
            SELECT m.id AS id, m.url AS url, m.thumbnailUrl AS thumbnailUrl,
                   m.mediaType AS mediaType, m.mediaOrder AS mediaOrder
            FROM PostMediaEntity m
            WHERE m.post.id = :postId
            ORDER BY m.mediaOrder ASC
            """)
    List<PostMediaConfirmView> findConfirmMediaUploadViewsByPostId(@Param("postId") UUID postId);
}
