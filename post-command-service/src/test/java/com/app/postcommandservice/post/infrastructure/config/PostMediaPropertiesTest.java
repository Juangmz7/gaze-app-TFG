package com.app.postcommandservice.post.infrastructure.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostMediaPropertiesTest {

    @Test
    void shouldDefaultUploadWindowToFortyEightHours() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getUploadWindow()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    void shouldAllowOverridingTheUploadWindow() {
        PostMediaProperties properties = new PostMediaProperties();
        properties.setUploadWindow(Duration.ofHours(12));

        assertThat(properties.getUploadWindow()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void shouldDefaultMaxTaggedUsersToThirty() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getMaxTaggedUsers()).isEqualTo(30);
    }

    @Test
    void shouldAllowOverridingMaxTaggedUsers() {
        PostMediaProperties properties = new PostMediaProperties();
        properties.setMaxTaggedUsers(5);

        assertThat(properties.getMaxTaggedUsers()).isEqualTo(5);
    }
}
