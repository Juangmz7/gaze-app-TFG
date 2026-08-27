package com.app.postcommandservice.comment.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentMapper;

@Repository
@RequiredArgsConstructor
public class CommentRepositoryImpl implements CommentRepository {

    private final CommentJpaRepository commentJpaRepository;
    private final CommentMapper commentMapper;

    @Override
    public Comment save(Comment comment) {
        return commentMapper.toDomain(commentJpaRepository.save(commentMapper.toEntity(comment)));
    }

    @Override
    public Comment saveAndFlush(Comment comment) {
        return commentMapper.toDomain(commentJpaRepository.saveAndFlush(commentMapper.toEntity(comment)));
    }

    @Override
    public Optional<Comment> findByIdAndPostId(UUID commentId, UUID postId) {
        return commentJpaRepository.findByIdAndPostId(commentId, postId).map(commentMapper::toDomain);
    }
}
