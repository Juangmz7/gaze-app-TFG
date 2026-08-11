package com.app.postcommandservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PostCommandServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostCommandServiceApplication.class, args);
    }

}
