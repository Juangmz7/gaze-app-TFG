package com.app.postcommandservice.post.domain.model;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import java.time.Instant;

public class Post {
    private PostId id;
    private UserId userId;
    private PostStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
