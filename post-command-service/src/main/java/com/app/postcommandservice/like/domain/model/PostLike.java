package com.app.postcommandservice.like.domain.model;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import java.time.Instant;

public class PostLike {
    private PostId postId;
    private UserId userId;
    private Instant createdAt;
}
