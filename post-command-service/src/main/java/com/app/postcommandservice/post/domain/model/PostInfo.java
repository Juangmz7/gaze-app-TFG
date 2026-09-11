package com.app.postcommandservice.post.domain.model;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.exception.InvalidPostInfoException;

public record PostInfo(String title, PostDescription description, PostTaggedUsers taggedUsers, PostTags tags, PostType postType) {
    public PostInfo {
        if (title != null && title.length() > 255) {
            throw new InvalidPostInfoException("title must be lower than or equal to 255");
        }
        if (description == null || taggedUsers == null || tags == null || postType == null) {
            throw new InvalidPostInfoException("post info fields must not be null");
        }
        taggedUsers = new PostTaggedUsers(new LinkedHashSet<>(taggedUsers.value()));
        tags = new PostTags(new LinkedHashSet<>(tags.value()));
    }
}
