package com.app.postcommandservice.post.infrastructure.config;

import java.time.Duration;
import java.util.Set;

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

    @Test
    void shouldDefaultMaxImageBytesToTenMegabytes() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getMaxImageBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void shouldDefaultMaxVideoBytesToTwoHundredMegabytes() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getMaxVideoBytes()).isEqualTo(200L * 1024 * 1024);
    }

    @Test
    void shouldDefaultMaxVideoDurationSecondsToNullMeaningUnlimited() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getMaxVideoDurationSeconds()).isNull();
    }

    @Test
    void shouldAllowOverridingMaxVideoDurationSeconds() {
        PostMediaProperties properties = new PostMediaProperties();
        properties.setMaxVideoDurationSeconds(120);

        assertThat(properties.getMaxVideoDurationSeconds()).isEqualTo(120);
    }

    @Test
    void shouldDefaultAllowedImageFormatsToJpegPngAndWebp() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getAllowedImageFormats()).containsExactlyInAnyOrder("JPEG", "PNG", "WEBP");
    }

    @Test
    void shouldDefaultAllowedVideoFormatsToMp4AndMov() {
        PostMediaProperties properties = new PostMediaProperties();

        assertThat(properties.getAllowedVideoFormats()).containsExactlyInAnyOrder("MP4", "MOV");
    }

    @Test
    void shouldAllowOverridingAllowedFormats() {
        PostMediaProperties properties = new PostMediaProperties();
        properties.setAllowedImageFormats(Set.of("JPEG"));
        properties.setAllowedVideoFormats(Set.of("MP4"));

        assertThat(properties.getAllowedImageFormats()).containsExactly("JPEG");
        assertThat(properties.getAllowedVideoFormats()).containsExactly("MP4");
    }
}
