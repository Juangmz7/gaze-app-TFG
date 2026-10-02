package com.app.postcommandservice.post.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PostMediaProperties.class)
public class PostMediaConfig {
}
