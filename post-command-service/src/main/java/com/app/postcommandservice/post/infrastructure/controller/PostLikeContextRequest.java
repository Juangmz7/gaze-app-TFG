package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import com.app.postcommandservice.like.domain.model.PostLikeContext;
import com.app.postcommandservice.like.domain.model.PostLikeSource;

public record PostLikeContextRequest(
        @NotBlank(message = "context.source is required")
        @Pattern(
                regexp = "home_feed|user_profile|search",
                message = "context.source must be one of: home_feed, user_profile, search"
        )
        String source,
        @NotNull(message = "context.feedPosition is required")
        @PositiveOrZero(message = "context.feedPosition must be zero or greater")
        Integer feedPosition
) {

    public PostLikeContext toDomain() {
        return new PostLikeContext(toSource(), feedPosition);
    }

    public PostLikeSource toSource() {
        return switch (source) {
            case "home_feed" -> PostLikeSource.HOME_FEED;
            case "user_profile" -> PostLikeSource.USER_PROFILE;
            case "search" -> PostLikeSource.SEARCH;
            default -> throw new IllegalArgumentException("Unsupported context.source: " + source);
        };
    }
}
