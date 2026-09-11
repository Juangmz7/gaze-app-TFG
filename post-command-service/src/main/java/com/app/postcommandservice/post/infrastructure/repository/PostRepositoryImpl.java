package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.infrastructure.mapper.PostMapper;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepository {

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;
    private final PostMapper postMapper;

    @Override
    @Transactional
    public Post save(Post post) {
        return save(post, false);
    }

    @Override
    @Transactional
    public Post saveAndFlush(Post post) {
        return save(post, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Post> findById(UUID postId) {
        return postJpaRepository.findById(postId).map(postMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Post> findByCollabId(UUID collabId) {
        return postJpaRepository.findFirstByCollabId(collabId).map(postMapper::toDomain);
    }

    private Post save(Post post, boolean flush) {
        var existing = postJpaRepository.findById(post.getId().value());
        if (existing.isEmpty()) {
            for (var media : post.getMedia()) {
                if (postMediaJpaRepository.findById(media.id()).isPresent()) {
                    throw new InvalidPostMediaException("media id belongs to another post");
                }
            }
            var saved = flush ? postJpaRepository.saveAndFlush(postMapper.toEntity(post))
                    : postJpaRepository.save(postMapper.toEntity(post));
            return postMapper.toDomain(saved);
        }

        var entity = existing.get();
        postMapper.updateEntity(post, entity);
        var existingById = entity.getMedia().stream().collect(java.util.stream.Collectors.toMap(
                PostMediaEntity::getId, item -> item));
        for (var media : post.getMedia()) {
            var local = existingById.get(media.id());
            if (local == null && postMediaJpaRepository.findById(media.id()).isPresent()) {
                throw new InvalidPostMediaException("media id belongs to another post");
            }
        }

        // Move persisted rows beyond both current and requested display ranges before reordering.
        int temporaryOffset = entity.getMedia().size() + post.getMedia().size() + 1;
        entity.getMedia().forEach(item -> item.setOrder(item.getOrder() + temporaryOffset));
        postJpaRepository.flush();

        var incomingIds = post.getMedia().stream().map(com.app.postcommandservice.post.domain.model.PostMedia::id)
                .collect(java.util.stream.Collectors.toSet());
        entity.getMedia().removeIf(item -> !incomingIds.contains(item.getId()));
        for (var media : post.getMedia()) {
            var target = existingById.get(media.id());
            if (target == null) {
                target = postMapper.toMediaEntity(media, entity);
                entity.getMedia().add(target);
            } else {
                target.setUrl(media.url());
                target.setThumbnailUrl(media.thumbnailUrl());
                target.setMediaType(media.mediaType());
                target.setDuration(media.duration());
                target.setOrder(media.order());
            }
        }
        var saved = flush ? postJpaRepository.saveAndFlush(entity) : postJpaRepository.save(entity);
        return postMapper.toDomain(saved);
    }
}
