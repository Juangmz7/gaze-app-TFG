package com.app.postcommandservice.like.infrastructure.repository;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.like.application.repository.PostLikeRepository;
import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeId;
import com.app.postcommandservice.like.infrastructure.mapper.PostLikeMapper;

@Repository
@RequiredArgsConstructor
public class PostLikeRepositoryImpl implements PostLikeRepository {

    private final PostLikeJpaRepository postLikeJpaRepository;
    private final PostLikeMapper postLikeMapper;

    @Override
    public boolean existsByPostIdAndUserId(UUID postId, UUID userId) {
        return postLikeJpaRepository.existsById(new PostLikeId(postId, userId));
    }

    @Override
    public PostLike save(PostLike postLike) {
        return postLikeMapper.toDomain(postLikeJpaRepository.save(postLikeMapper.toEntity(postLike)));
    }

    @Override
    public void deleteByPostIdAndUserId(UUID postId, UUID userId) {
        postLikeJpaRepository.deleteById(new PostLikeId(postId, userId));
    }
}
