package com.app.postcommandservice.view.application.repository;

import java.util.UUID;

import com.app.postcommandservice.view.domain.model.PostView;

public interface PostViewRepository {

    long countByPostIdAndUserId(UUID postId, UUID userId);

    PostView save(PostView postView);
}
