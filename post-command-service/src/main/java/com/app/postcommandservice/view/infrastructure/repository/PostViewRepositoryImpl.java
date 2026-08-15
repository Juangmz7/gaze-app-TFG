package com.app.postcommandservice.view.infrastructure.repository;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.view.application.repository.PostViewRepository;
import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.infrastructure.mapper.PostViewMapper;

@Repository
@RequiredArgsConstructor
public class PostViewRepositoryImpl implements PostViewRepository {

    private final PostViewJpaRepository postViewJpaRepository;
    private final PostViewMapper postViewMapper;

    @Override
    public long countByPostIdAndUserId(UUID postId, UUID userId) {
        return postViewJpaRepository.countByPostIdAndUserId(postId, userId);
    }

    @Override
    public PostView save(PostView postView) {
        return postViewMapper.toDomain(postViewJpaRepository.save(postViewMapper.toEntity(postView)));
    }
}
