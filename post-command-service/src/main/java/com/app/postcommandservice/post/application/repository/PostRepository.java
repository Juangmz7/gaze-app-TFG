package com.app.postcommandservice.post.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.Post;

public interface PostRepository {

    Post save(Post post);

    Post saveAndFlush(Post post);

    Optional<Post> findById(UUID postId);

    Optional<Post> findByCollabId(UUID collabId);
}
