package com.app.postcommandservice.view.application.repository;

import java.util.UUID;

public interface PostViewValidationRepository {

    boolean existsPost(UUID postId);
}
