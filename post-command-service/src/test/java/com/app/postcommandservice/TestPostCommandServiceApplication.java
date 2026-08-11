package com.app.postcommandservice;

import org.springframework.boot.SpringApplication;

public class TestPostCommandServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(PostCommandServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("test")
                .run(args);
    }

}
