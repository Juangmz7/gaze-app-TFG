package com.app.postcommandservice.post.infrastructure.azure;

import java.util.HashSet;
import java.util.Set;

import com.app.postcommandservice.post.application.port.GeneratedMediaUrls;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.shared.infrastructure.azure.config.AzureStorageProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AzureMediaUrlGeneratorTest {

    private AzureStorageProperties properties;
    private AzureMediaUrlGenerator generator;

    @BeforeEach
    void setUp() {
        properties = new AzureStorageProperties();
        properties.setAccountUrl("https://myaccount.blob.core.windows.net");
        properties.setContainer("post-media");
        generator = new AzureMediaUrlGenerator(properties);
    }

    @Test
    void shouldGenerateTwoDistinctUuidBasedUrlsForVideo() {
        GeneratedMediaUrls result = generator.generate(MediaType.VIDEO);

        assertThat(result.url()).isNotEqualTo(result.thumbnailUrl());
        assertThat(result.url()).startsWith("https://myaccount.blob.core.windows.net/post-media/");
        assertThat(result.thumbnailUrl()).startsWith("https://myaccount.blob.core.windows.net/post-media/");
    }

    @Test
    void shouldGenerateOneUuidBasedUrlForImageAndReuseItAsThumbnailUrl() {
        GeneratedMediaUrls result = generator.generate(MediaType.IMAGE);

        assertThat(result.url()).isEqualTo(result.thumbnailUrl());
        assertThat(result.url()).startsWith("https://myaccount.blob.core.windows.net/post-media/");
    }

    @Test
    void shouldNeverGenerateTheSameUrlTwiceAcrossManyInvocations() {
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            GeneratedMediaUrls image = generator.generate(MediaType.IMAGE);
            GeneratedMediaUrls video = generator.generate(MediaType.VIDEO);

            assertThat(seen.add(image.url())).isTrue();
            assertThat(seen.add(video.url())).isTrue();
            assertThat(seen.add(video.thumbnailUrl())).isTrue();
        }
    }

    @Test
    void shouldBuildUrlsUsingTheConfiguredAccountUrlAndContainer() {
        properties.setAccountUrl("http://127.0.0.1:10000/devstoreaccount1");
        properties.setContainer("post-media-dev");

        GeneratedMediaUrls result = generator.generate(MediaType.IMAGE);

        assertThat(result.url()).startsWith("http://127.0.0.1:10000/devstoreaccount1/post-media-dev/");

        properties.setAccountUrl("https://otheraccount.blob.core.windows.net");
        properties.setContainer("other-container");

        GeneratedMediaUrls afterChange = generator.generate(MediaType.IMAGE);

        assertThat(afterChange.url()).startsWith("https://otheraccount.blob.core.windows.net/other-container/");
    }

    @Test
    void shouldTolerateTrailingSlashOnAccountUrl() {
        properties.setAccountUrl("https://myaccount.blob.core.windows.net/");

        GeneratedMediaUrls result = generator.generate(MediaType.IMAGE);

        assertThat(result.url()).doesNotContain("//post-media");
        assertThat(result.url()).startsWith("https://myaccount.blob.core.windows.net/post-media/");
    }

    @Test
    void shouldRejectNullMediaType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(NullPointerException.class);
    }
}
