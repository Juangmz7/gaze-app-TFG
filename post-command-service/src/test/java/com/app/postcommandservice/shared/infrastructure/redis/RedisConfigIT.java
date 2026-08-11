package com.app.postcommandservice.shared.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.utility.TestcontainersConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisConfigIT {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void testRedisTemplateBeanIsAvailableAndWorks() {
        assertNotNull(redisTemplate, "RedisTemplate should be available");

        redisTemplate.opsForValue().set("test-key", "test-value");
        Object retrieved = redisTemplate.opsForValue().get("test-key");
        
        assertEquals("test-value", retrieved, "Redis should be able to store and retrieve data");
    }
}
