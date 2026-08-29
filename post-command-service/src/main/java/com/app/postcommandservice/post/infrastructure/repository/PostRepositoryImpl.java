package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.infrastructure.mapper.PostMapper;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepository {

    private final PostJpaRepository postJpaRepository;
    private final PostMapper postMapper;

    @Override
    public Post save(Post post) {
        return postMapper.toDomain(postJpaRepository.save(postMapper.toEntity(post)));
    }

    @Override
    public Post saveAndFlush(Post post) {
        return postMapper.toDomain(postJpaRepository.saveAndFlush(postMapper.toEntity(post)));
    }

    @Override
    public Optional<Post> findById(UUID postId) {
        return postJpaRepository.findById(postId).map(postMapper::toDomain);
    }

    @Override
    public Optional<Post> findByCollabId(UUID collabId) {
        return postJpaRepository.findFirstByCollabId(collabId).map(postMapper::toDomain);
    }
}
