package com.app.socialservice;

import org.springframework.boot.SpringApplication;

public class TestSocialServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(SocialServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
