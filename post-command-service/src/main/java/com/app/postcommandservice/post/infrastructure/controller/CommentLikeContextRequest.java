package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import com.app.postcommandservice.commentlike.domain.model.CommentLikeContext;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;

public record CommentLikeContextRequest(
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

    public CommentLikeContext toDomain() {
        return new CommentLikeContext(toSource(), feedPosition);
    }

    public CommentLikeSource toSource() {
        return switch (source) {
            case "home_feed" -> CommentLikeSource.HOME_FEED;
            case "user_profile" -> CommentLikeSource.USER_PROFILE;
            case "search" -> CommentLikeSource.SEARCH;
            default -> throw new IllegalArgumentException("Unsupported context.source: " + source);
        };
    }
}
