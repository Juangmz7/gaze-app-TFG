package com.app.socialservice.shared.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.app.socialservice.user.application.cache.CacheNames;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.PublicUserProfile;
import com.app.socialservice.user.application.dto.RecommendedUserResponse;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableCaching
public class RedisConfig {

    static final Duration PROFILE_TTL = Duration.ofMinutes(30);
    static final Duration RECOMMENDED_USERS_TTL = Duration.ofMinutes(5);
    private static final int CLEAR_SCAN_BATCH_SIZE = 1000;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        template.setValueSerializer(RedisSerializer.json());
        template.setHashValueSerializer(RedisSerializer.json());

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Every cache stores one declared type, read back with that type: the generic JSON
     * serializer writes class names and cannot rebuild the immutable maps of the
     * records. Transaction aware, so a put or evict inside a transaction runs after the
     * commit, and immediate, so it has happened when the call returns. A clear scans
     * instead of KEYS, which blocks Redis.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        JsonMapper jsonMapper = JsonMapper.builder().build();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.json())
                )
                .disableCachingNullValues();

        JavaType recommendedUsers = jsonMapper.getTypeFactory()
                .constructCollectionType(List.class, RecommendedUserResponse.class);

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                CacheNames.OWN_PROFILE, typed(defaultConfig, jsonMapper,
                        jsonMapper.constructType(OwnUserProfileResponse.class)).entryTtl(PROFILE_TTL),
                CacheNames.PUBLIC_PROFILE, typed(defaultConfig, jsonMapper,
                        jsonMapper.constructType(PublicUserProfile.class)).entryTtl(PROFILE_TTL),
                CacheNames.RECOMMENDED_USERS, typed(defaultConfig, jsonMapper, recommendedUsers)
                        .entryTtl(RECOMMENDED_USERS_TTL)
        );

        // Immediate writes: Spring Data Redis 4 sends puts and evictions asynchronously by
        // default, so a read right after an eviction could still find the old entry, and a
        // put of a stale read could land after the eviction that was meant to replace it
        RedisCacheWriter cacheWriter = RedisCacheWriter.create(factory, writer -> writer
                .batchStrategy(BatchStrategies.scan(CLEAR_SCAN_BATCH_SIZE))
                .immediateWrites(true));

        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }

    private static RedisCacheConfiguration typed(RedisCacheConfiguration base, JsonMapper jsonMapper, JavaType type) {
        return base.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                new JacksonJsonRedisSerializer<>(jsonMapper, type)));
    }
}
