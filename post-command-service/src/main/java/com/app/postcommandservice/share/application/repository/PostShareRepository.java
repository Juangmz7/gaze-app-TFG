package com.app.postcommandservice.share.application.repository;

import java.util.UUID;

import com.app.postcommandservice.share.domain.model.PostShare;

public interface PostShareRepository {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    PostShare save(PostShare postShare);
}
