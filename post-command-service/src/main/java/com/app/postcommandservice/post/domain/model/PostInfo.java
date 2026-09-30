package com.app.postcommandservice.post.domain.model;

import java.util.Objects;

import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record PostInfo(
        PostDescription description,
        PostTaggedUsers taggedUsers,
        PostTags tags,
        PostType postType
) {

    public PostInfo {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        Objects.requireNonNull(postType, "postType must not be null");
    }
}
