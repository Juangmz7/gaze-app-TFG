package com.app.postcommandservice.post.domain.model;

import java.util.Objects;

import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.domain.exception.InvalidPostTitleException;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record PostInfo(
        String title,
        PostDescription description,
        PostTaggedUsers taggedUsers,
        PostTags tags,
        PostType postType
) {

    private static final int TITLE_MAX_LENGTH = 255;

    public PostInfo {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(taggedUsers, "taggedUsers must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        Objects.requireNonNull(postType, "postType must not be null");

        if (title != null) {
            if (!StringUtils.hasText(title)) {
                throw new InvalidPostTitleException("Post title must not be blank when provided");
            }
            if (title.length() > TITLE_MAX_LENGTH) {
                throw new InvalidPostTitleException("Post title must be lower than or equal to " + TITLE_MAX_LENGTH);
            }
        }
    }
}
