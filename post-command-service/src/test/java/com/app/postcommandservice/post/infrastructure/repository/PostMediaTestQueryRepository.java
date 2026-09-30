package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

/**
 * Test-only finder for {@code PostMediaEntity}, kept out of the production
 * {@link PostMediaJpaRepository} because nothing in application/production code needs it —
 * it exists solely to let integration tests assert persisted media ordering without tripping
 * {@code PostEntity.media}'s lazy-loading (which requires an open Hibernate session that the
 * test, running outside a transaction, does not have).
 */
public interface PostMediaTestQueryRepository extends JpaRepository<PostMediaEntity, UUID> {

    List<PostMediaEntity> findByPost_IdOrderByMediaOrderAsc(UUID postId);
}
