package com.app.socialservice.user.infrastructure.repository;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserStatsRepositoryImplIntegrationTest {

    private static final String USER_STATS_KEY_PATTERN = "user:stats:%s:%s";

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private UserStatsRepository userStatsRepository;

    @jakarta.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        flushRedis();
    }

    @Test
    void shouldUseAtomicRedisOperationsForFollowerCounters() throws Exception {
        var userId = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(8);

        try {
            var tasks = java.util.stream.IntStream.range(0, 200)
                    .<Callable<Void>>mapToObj(index -> () -> {
                        userStatsRepository.incrementFollowersCount(userId);
                        return null;
                    })
                    .toList();

            var futures = executor.invokeAll(tasks);
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(readCounter(userId, "followers")).isEqualTo(200L);

        userStatsRepository.decrementFollowersCount(userId);
        userStatsRepository.decrementFollowersCount(userId);

        assertThat(readCounter(userId, "followers")).isEqualTo(198L);
        assertThat(userStatsRepository.getFollowersCount(userId)).isEqualTo(198L);
    }

    @Test
    void shouldReturnZeroForMissingCountersAndReadStoredFollowingCounter() {
        var userId = UUID.randomUUID();

        assertThat(userStatsRepository.getFollowersCount(userId)).isZero();
        assertThat(userStatsRepository.getFollowingCount(userId)).isZero();

        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, "following"), "9");

        assertThat(userStatsRepository.getFollowingCount(userId)).isEqualTo(9L);
    }

    @Test
    void shouldNotCreateUserStatsTableInPostgres() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'user_stats'
                """,
                Integer.class
        );

        assertThat(tableCount).isZero();
    }

    private void flushRedis() {
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    private long readCounter(UUID userId, String counterName) {
        var rawValue = stringRedisTemplate.opsForValue().get(buildCounterKey(userId, counterName));
        if (rawValue == null) {
            return 0L;
        }
        return Long.parseLong(rawValue);
    }

    private String buildCounterKey(UUID userId, String counterName) {
        return String.format(USER_STATS_KEY_PATTERN, userId, counterName);
    }
}
