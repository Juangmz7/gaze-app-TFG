package com.app.postcommandservice.post.domain.model;

import com.app.postcommandservice.post.domain.model.valueobj.*;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import java.time.Instant;

public class Post {
    private PostId id;
    private UserId userId;
    private PostDescription description;
    private PostTaggedUsers taggedUsers;
    private PostTags tags;
    private PostStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
