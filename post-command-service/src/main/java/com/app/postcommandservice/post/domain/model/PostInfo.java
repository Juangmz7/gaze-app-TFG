package com.app.postcommandservice.post.domain.model;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record PostInfo(String title, PostDescription description, PostTaggedUsers taggedUsers, PostTags tags, PostType postType) {
    public PostInfo {
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("title must be lower than or equal to 255");
        }
        description = Objects.requireNonNull(description, "description must not be null");
        taggedUsers = Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        tags = Objects.requireNonNull(tags, "tags must not be null");
        postType = Objects.requireNonNull(postType, "postType must not be null");
        taggedUsers = new PostTaggedUsers(new LinkedHashSet<>(taggedUsers.value()));
        tags = new PostTags(new LinkedHashSet<>(tags.value()));
    }
}
