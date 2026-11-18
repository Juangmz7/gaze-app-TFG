package com.app.socialservice.user.infrastructure.redis;

import java.util.UUID;

import com.app.socialservice.user.infrastructure.repository.JpaUserStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-off move of the counters Redis held (user:stats:{userId}:{counter}) to
 * PostgreSQL, at startup. The post count is copied into user_stats unless a row is
 * already there; followers and following are counted from follows now, so their keys
 * are only removed. Every key is deleted once handled, so the next start finds none
 * and this does nothing. Remove it once every environment has started with it.
 */
@Slf4j
@Component
public class LegacyRedisUserStatsMigration implements ApplicationRunner {

    private static final String KEY_PREFIX = "user:stats:";
    private static final String POST_COUNT_SUFFIX = ":postCount";
    private static final int SCAN_BATCH_SIZE = 500;

    private final StringRedisTemplate stringRedisTemplate;
    private final JpaUserStatsRepository jpaUserStatsRepository;
    private final TransactionTemplate transactionTemplate;

    public LegacyRedisUserStatsMigration(
            StringRedisTemplate stringRedisTemplate,
            JpaUserStatsRepository jpaUserStatsRepository,
            PlatformTransactionManager transactionManager) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jpaUserStatsRepository = jpaUserStatsRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        var copied = 0;
        var removed = 0;
        var options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(SCAN_BATCH_SIZE).build();
        try (var keys = stringRedisTemplate.scan(options)) {
            while (keys.hasNext()) {
                var key = keys.next();
                if (key.endsWith(POST_COUNT_SUFFIX) && copyPostCount(key)) {
                    copied++;
                }
                stringRedisTemplate.delete(key);
                removed++;
            }
        } catch (RuntimeException exception) {
            // The keys left are handled on the next start
            log.warn("Moving the user counters from Redis to PostgreSQL stopped after {} keys", removed, exception);
            return;
        }

        if (removed > 0) {
            log.info("Moved the user counters from Redis to PostgreSQL: post_counts_copied={} redis_keys_removed={}",
                    copied, removed);
        }
    }

    private boolean copyPostCount(String key) {
        var userId = parseUserId(key);
        var value = stringRedisTemplate.opsForValue().get(key);
        if (userId == null || value == null) {
            return false;
        }

        long postCount;
        try {
            postCount = Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            log.warn("Skipping the Redis post count {} of user {}: not a number", value, userId);
            return false;
        }

        Integer inserted = transactionTemplate.execute(
                status -> jpaUserStatsRepository.insertPostCountIfAbsent(userId, postCount));
        return inserted != null && inserted > 0;
    }

    private UUID parseUserId(String key) {
        var rawUserId = key.substring(KEY_PREFIX.length(), key.length() - POST_COUNT_SUFFIX.length());
        try {
            return UUID.fromString(rawUserId);
        } catch (IllegalArgumentException exception) {
            log.warn("Skipping the Redis key {}: no user id", key);
            return null;
        }
    }
}
