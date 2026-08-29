package com.app.postcommandservice.share.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.postcommandservice.share.infrastructure.entity.PostShareEntity;
import com.app.postcommandservice.share.infrastructure.entity.PostShareId;

public interface PostShareJpaRepository extends JpaRepository<PostShareEntity, PostShareId> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PostShareEntity share
            where share.id.postId = :postId
              and share.id.userId = :userId
            """)
    int deleteByPostIdAndUserId(@Param("postId") UUID postId, @Param("userId") UUID userId);
}
