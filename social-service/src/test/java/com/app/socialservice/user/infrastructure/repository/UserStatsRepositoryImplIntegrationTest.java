package com.app.socialservice.user.infrastructure.repository;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import com.app.socialservice.user.infrastructure.redis.LegacyRedisUserStatsMigration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserStatsRepositoryImplIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private UserStatsRepository userStatsRepository;

    @jakarta.annotation.Resource
    private LegacyRedisUserStatsMigration legacyRedisUserStatsMigration;

    @jakarta.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbcTemplate;

    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldCountEveryConcurrentPostCountIncrementOnce() throws Exception {
        var userId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);
        var executor = Executors.newFixedThreadPool(8);

        try {
            var tasks = IntStream.range(0, 200)
                    .<Callable<Void>>mapToObj(index -> () -> {
                        transactions.executeWithoutResult(status -> userStatsRepository.incrementPostCount(userId));
                        return null;
                    })
                    .toList();
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(userStatsRepository.getStats(userId).postCount()).isEqualTo(200L);

        transactions.executeWithoutResult(status -> {
            userStatsRepository.decrementPostCount(userId);
            userStatsRepository.decrementPostCount(userId);
        });

        assertThat(userStatsRepository.getStats(userId).postCount()).isEqualTo(198L);
    }

    @Test
    void shouldNeverTakeThePostCountBelowZero() {
        var userId = UUID.randomUUID();

        // A delete whose create was never counted
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> userStatsRepository.decrementPostCount(userId));

        assertThat(userStatsRepository.getStats(userId).postCount()).isZero();
    }

    @Test
    void shouldCountFollowersAndFollowingFromTheActiveFollowsOnly() {
        var userId = UUID.randomUUID();
        insertFollow(UUID.randomUUID(), userId, "ACTIVE");
        insertFollow(UUID.randomUUID(), userId, "ACTIVE");
        insertFollow(UUID.randomUUID(), userId, "REMOVED");
        insertFollow(UUID.randomUUID(), userId, "BLOCKED");
        insertFollow(userId, UUID.randomUUID(), "ACTIVE");
        insertFollow(userId, UUID.randomUUID(), "REMOVED");

        assertThat(userStatsRepository.getStats(userId)).isEqualTo(new UserStats(2, 1, 0));
    }

    @Test
    void shouldReturnZerosForAUserWithoutStats() {
        assertThat(userStatsRepository.getStats(UUID.randomUUID())).isEqualTo(UserStats.EMPTY);
    }

    @Test
    void shouldMoveTheRedisPostCountsAndDropTheRedisFollowCounters() {
        var migratedUser = UUID.randomUUID();
        var userWithRow = UUID.randomUUID();
        stringRedisTemplate.opsForValue().set("user:stats:" + migratedUser + ":postCount", "7");
        stringRedisTemplate.opsForValue().set("user:stats:" + migratedUser + ":followers", "3");
        stringRedisTemplate.opsForValue().set("user:stats:" + userWithRow + ":postCount", "40");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> userStatsRepository.incrementPostCount(userWithRow));

        legacyRedisUserStatsMigration.run(null);

        assertThat(userStatsRepository.getStats(migratedUser).postCount()).isEqualTo(7L);
        // A row PostgreSQL already has is newer than the Redis value
        assertThat(userStatsRepository.getStats(userWithRow).postCount()).isEqualTo(1L);
        assertThat(stringRedisTemplate.keys("user:stats:" + migratedUser + ":*")).isEmpty();
        assertThat(stringRedisTemplate.keys("user:stats:" + userWithRow + ":*")).isEmpty();
    }

    private void insertFollow(UUID followerId, UUID followedId, String status) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, followed_id, status, created_at) VALUES (?, ?, ?, ?)",
                followerId, followedId, status, java.sql.Timestamp.from(Instant.now()));
    }
}
