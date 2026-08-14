package com.app.postcommandservice.post.infrastructure.controller;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.like.domain.model.PostLikeContext;
import com.app.postcommandservice.like.domain.model.PostLikeSource;

import static org.assertj.core.api.Assertions.assertThat;

class PostLikeRequestTest {

    @Test
    void shouldConvertLowercaseContextValuesToLikeDomainContext() {
        var context = new PostLikeContextRequest("home_feed", 5);

        PostLikeContext domainContext = context.toDomain();

        assertThat(domainContext.source()).isEqualTo(PostLikeSource.HOME_FEED);
        assertThat(domainContext.feedPosition()).isEqualTo(5);
    }
}
