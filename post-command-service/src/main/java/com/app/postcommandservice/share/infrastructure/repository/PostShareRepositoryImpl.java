package com.app.postcommandservice.share.infrastructure.repository;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.domain.model.PostShare;
import com.app.postcommandservice.share.infrastructure.entity.PostShareId;
import com.app.postcommandservice.share.infrastructure.mapper.PostShareMapper;

@Repository
@RequiredArgsConstructor
public class PostShareRepositoryImpl implements PostShareRepository {

    private final PostShareJpaRepository postShareJpaRepository;
    private final PostShareMapper postShareMapper;

    @Override
    public boolean existsByPostIdAndUserId(UUID postId, UUID userId) {
        return postShareJpaRepository.existsById(new PostShareId(postId, userId));
    }

    @Override
    public int deleteByPostIdAndUserId(UUID postId, UUID userId) {
        return postShareJpaRepository.deleteByPostIdAndUserId(postId, userId);
    }

    @Override
    public PostShare save(PostShare postShare) {
        return postShareMapper.toDomain(postShareJpaRepository.save(postShareMapper.toEntity(postShare)));
    }
}
