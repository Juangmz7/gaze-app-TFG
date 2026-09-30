package com.app.postcommandservice.post.infrastructure.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.mapper.PostMapper;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepository {

    /**
     * Large enough to never collide with any realistic final 1-based media order value,
     * used to temporarily stage existing media orders out of range while reordering so the
     * (post_id, media_order) unique constraint is never violated mid-flush.
     */
    private static final int MEDIA_ORDER_STAGING_OFFSET = 1_000_000;

    private final PostJpaRepository postJpaRepository;
    private final PostMapper postMapper;

    @Override
    public Post save(Post post) {
        return postMapper.toDomain(postJpaRepository.save(postMapper.toEntity(post)));
    }

    @Override
    public Post saveAndFlush(Post post) {
        PostEntity existing = postJpaRepository.findById(post.getId().value())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected an already-persisted post to update but none was found: " + post.getId().value()));

        stageMediaOrders(existing);
        postJpaRepository.saveAndFlush(existing);
        reconcileMedia(existing, post.getMedia());
        applyScalarChanges(existing, post);
        return postMapper.toDomain(postJpaRepository.saveAndFlush(existing));
    }

    @Override
    public Optional<Post> findById(UUID postId) {
        return postJpaRepository.findById(postId).map(postMapper::toDomain);
    }

    @Override
    public Optional<Post> findByCollabId(UUID collabId) {
        return postJpaRepository.findFirstByCollabId(collabId).map(postMapper::toDomain);
    }

    private void stageMediaOrders(PostEntity existing) {
        for (PostMediaEntity mediaEntity : existing.getMedia()) {
            mediaEntity.setMediaOrder(mediaEntity.getMediaOrder() + MEDIA_ORDER_STAGING_OFFSET);
        }
    }

    private void reconcileMedia(PostEntity existing, List<PostMedia> incomingMedia) {
        Map<UUID, PostMedia> incomingById = new LinkedHashMap<>();
        for (PostMedia postMedia : incomingMedia) {
            incomingById.put(postMedia.getId(), postMedia);
        }

        existing.getMedia().removeIf(mediaEntity -> !incomingById.containsKey(mediaEntity.getId()));

        Map<UUID, PostMediaEntity> existingById = new HashMap<>();
        for (PostMediaEntity mediaEntity : existing.getMedia()) {
            existingById.put(mediaEntity.getId(), mediaEntity);
        }

        for (PostMedia postMedia : incomingMedia) {
            PostMediaEntity mediaEntity = existingById.get(postMedia.getId());
            if (mediaEntity == null) {
                existing.addMedia(postMapper.toMediaEntity(postMedia));
            } else {
                mediaEntity.setUrl(postMedia.getUrl());
                mediaEntity.setThumbnailUrl(postMedia.getThumbnailUrl());
                mediaEntity.setMediaType(postMedia.getMediaType());
                mediaEntity.setDuration(postMedia.getDuration());
                mediaEntity.setMediaOrder(postMedia.getOrder());
            }
        }
    }

    private void applyScalarChanges(PostEntity existing, Post post) {
        existing.setCollabId(post.getCollabId());
        existing.setPostType(post.getPostType());
        existing.setDescription(post.getDescription().value());
        existing.setTaggedUsers(new ArrayList<>(post.getTaggedUsers().value()));
        existing.setTags(new ArrayList<>(post.getTags().value()));
        existing.setStatus(post.getStatus());
        existing.touch();
    }
}
