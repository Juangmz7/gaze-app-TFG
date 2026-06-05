package com.app.postcommandservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostCommandServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
