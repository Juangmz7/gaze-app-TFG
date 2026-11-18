package com.app.socialservice.shared.testutil;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import com.app.socialservice.block.infrastructure.repository.JpaBlockRepository;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.entity.UserNode;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SocialIntegrationSeeder {

    private SocialIntegrationSeeder() {
    }

    public static UserEntity seedUser(JpaUserRepository userRepository, UserEntity userEntity) {
        Objects.requireNonNull(userRepository, "userRepository must not be null");
        Objects.requireNonNull(userEntity, "userEntity must not be null");

        return userRepository.save(userEntity);
    }

    public static UserNode seedUserNode(UserNodeRepository userNodeRepository, UUID userId) {
        Objects.requireNonNull(userNodeRepository, "userNodeRepository must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        return userNodeRepository.save(UserNode.builder().id(userId).build());
    }

    public static BlockEntity seedBlock(
            JpaBlockRepository blockRepository,
            UUID blockerId,
            UUID blockedId,
            Instant createdAt
    ) {
        Objects.requireNonNull(blockRepository, "blockRepository must not be null");
        Objects.requireNonNull(blockerId, "blockerId must not be null");
        Objects.requireNonNull(blockedId, "blockedId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        return blockRepository.save(new BlockEntity(
                new BlockEntityId(blockerId, blockedId),
                createdAt
        ));
    }

    public static FollowEntity seedFollow(JpaFollowRepository followRepository, FollowEntity followEntity) {
        Objects.requireNonNull(followRepository, "followRepository must not be null");
        Objects.requireNonNull(followEntity, "followEntity must not be null");

        return followRepository.save(followEntity);
    }

    public static void seedGraphFollow(Neo4jClient neo4jClient, UUID followerId, UUID followedId) {
        Objects.requireNonNull(neo4jClient, "neo4jClient must not be null");
        Objects.requireNonNull(followerId, "followerId must not be null");
        Objects.requireNonNull(followedId, "followedId must not be null");

        neo4jClient.query("""
                MATCH (follower:User {id: $followerId})
                MATCH (followed:User {id: $followedId})
                MERGE (follower)-[:FOLLOWS]->(followed)
                """)
                .bind(followerId.toString()).to("followerId")
                .bind(followedId.toString()).to("followedId")
                .run();
    }

    /**
     * Followers and following are counted from the active follows: this adds active follows
     * from or to new users until the totals are the given ones, counting those the test
     * already seeded. The post count is the user_stats row.
     */
    public static void seedUserStats(
            JdbcTemplate jdbcTemplate,
            UUID userId,
            long followersCount,
            long followingCount,
            long postCount
    ) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        for (long i = readCounter(jdbcTemplate, userId, UserStatsTestConstants.FOLLOWERS_COUNTER); i < followersCount; i++) {
            insertActiveFollow(jdbcTemplate, UUID.randomUUID(), userId);
        }
        for (long i = readCounter(jdbcTemplate, userId, UserStatsTestConstants.FOLLOWING_COUNTER); i < followingCount; i++) {
            insertActiveFollow(jdbcTemplate, userId, UUID.randomUUID());
        }
        jdbcTemplate.update("""
                INSERT INTO user_stats (user_id, post_count) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET post_count = EXCLUDED.post_count
                """, userId, postCount);
    }

    public static long readCounter(JdbcTemplate jdbcTemplate, UUID userId, String counterName) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(counterName, "counterName must not be null");

        var sql = switch (counterName) {
            case UserStatsTestConstants.FOLLOWERS_COUNTER ->
                    "SELECT count(*) FROM follows WHERE followed_id = ? AND status = 'ACTIVE'";
            case UserStatsTestConstants.FOLLOWING_COUNTER ->
                    "SELECT count(*) FROM follows WHERE follower_id = ? AND status = 'ACTIVE'";
            case UserStatsTestConstants.POST_COUNT_COUNTER ->
                    "SELECT COALESCE((SELECT post_count FROM user_stats WHERE user_id = ?), 0)";
            default -> throw new IllegalArgumentException("Unknown counter " + counterName);
        };
        var value = jdbcTemplate.queryForObject(sql, Long.class, userId);
        return value == null ? 0L : value;
    }

    private static void insertActiveFollow(JdbcTemplate jdbcTemplate, UUID followerId, UUID followedId) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, followed_id, status, created_at) VALUES (?, ?, 'ACTIVE', ?)",
                followerId, followedId, java.sql.Timestamp.from(Instant.now()));
    }
}
