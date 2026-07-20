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
import org.springframework.data.redis.core.StringRedisTemplate;

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

    public static void seedUserStats(
            StringRedisTemplate stringRedisTemplate,
            UUID userId,
            long followersCount,
            long followingCount,
            long postCount
    ) {
        Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, UserStatsTestConstants.FOLLOWERS_COUNTER),
                String.valueOf(followersCount));
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, UserStatsTestConstants.FOLLOWING_COUNTER),
                String.valueOf(followingCount));
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, UserStatsTestConstants.POST_COUNT_COUNTER),
                String.valueOf(postCount));
    }

    public static long readCounter(StringRedisTemplate stringRedisTemplate, UUID userId, String counterName) {
        Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(counterName, "counterName must not be null");

        var rawValue = stringRedisTemplate.opsForValue().get(buildCounterKey(userId, counterName));
        if (rawValue == null) {
            return 0L;
        }

        return Long.parseLong(rawValue);
    }

    public static String buildCounterKey(UUID userId, String counterName) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(counterName, "counterName must not be null");

        return String.format(UserStatsTestConstants.USER_STATS_KEY_PATTERN, userId, counterName);
    }
}
