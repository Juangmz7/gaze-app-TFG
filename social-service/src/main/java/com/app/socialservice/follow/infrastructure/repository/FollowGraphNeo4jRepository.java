package com.app.socialservice.follow.infrastructure.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.app.socialservice.follow.application.dto.RecommendedFollowCandidate;
import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FollowGraphNeo4jRepository implements FollowGraphRepository {

    private final Neo4jClient neo4jClient;

    @Override
    public void createFollowRelationship(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("followerUserId must not equal followedUserId");
        }

        var result = neo4jClient.query("""
                OPTIONAL MATCH (follower:User {id: $followerUserId})
                OPTIONAL MATCH (followed:User {id: $followedUserId})
                FOREACH (_ IN CASE WHEN follower IS NOT NULL AND followed IS NOT NULL THEN [1] ELSE [] END |
                    MERGE (follower)-[:FOLLOWS]->(followed)
                )
                RETURN follower IS NOT NULL AS followerExists,
                       followed IS NOT NULL AS followedExists
                """)
                .bind(followerUserId.toString()).to("followerUserId")
                .bind(followedUserId.toString()).to("followedUserId")
                .fetch()
                .one();

        if (result.isEmpty()
                || !Boolean.TRUE.equals(result.get().get("followerExists"))
                || !Boolean.TRUE.equals(result.get().get("followedExists"))) {
            throw new IllegalStateException(String.format(
                    "Cannot create follow relationship because Neo4j user nodes are missing for follower %s and followed %s",
                    followerUserId,
                    followedUserId
            ));
        }
    }

    @Override
    public void deleteBidirectionalFollowRelationship(UUID firstUserId, UUID secondUserId) {
        if (firstUserId == null) {
            throw new IllegalArgumentException("firstUserId must not be null");
        }
        if (secondUserId == null) {
            throw new IllegalArgumentException("secondUserId must not be null");
        }

        neo4jClient.query("""
                MATCH (from:User)-[follow:FOLLOWS]->(to:User)
                WHERE (from.id = $firstUserId AND to.id = $secondUserId)
                   OR (from.id = $secondUserId AND to.id = $firstUserId)
                DELETE follow
                """)
                .bind(firstUserId.toString()).to("firstUserId")
                .bind(secondUserId.toString()).to("secondUserId")
                .run();
    }

    @Override
    public void deleteFollowRelationship(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("followerUserId must not equal followedUserId");
        }

        neo4jClient.query("""
                OPTIONAL MATCH (follower:User {id: $followerUserId})
                OPTIONAL MATCH (followed:User {id: $followedUserId})
                OPTIONAL MATCH (follower)-[follow:FOLLOWS]->(followed)
                FOREACH (_ IN CASE WHEN follow IS NOT NULL THEN [1] ELSE [] END |
                    DELETE follow
                )
                """)
                .bind(followerUserId.toString()).to("followerUserId")
                .bind(followedUserId.toString()).to("followedUserId")
                .run();
    }

    @Override
    public List<RecommendedFollowCandidate> findRecommendedUsers(UUID requesterUserId, Set<UUID> blockedUserIds, int limit) {
        if (requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId must not be null");
        }
        if (blockedUserIds == null) {
            throw new IllegalArgumentException("blockedUserIds must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        var excludedUserIds = blockedUserIds.stream()
                .map(UUID::toString)
                .toList();

        return neo4jClient.query("""
                MATCH (requester:User {id: $requesterUserId})-[:FOLLOWS]->(mutual:User)-[:FOLLOWS]->(candidate:User)
                WHERE candidate.id <> $requesterUserId
                  AND NOT (requester)-[:FOLLOWS]->(candidate)
                  AND NOT candidate.id IN $excludedUserIds
                WITH candidate, count(DISTINCT mutual) AS commonConnections
                ORDER BY commonConnections DESC, candidate.id ASC
                RETURN candidate.id AS userId, commonConnections AS commonConnections
                LIMIT $limit
                """)
                .bind(requesterUserId.toString()).to("requesterUserId")
                .bind(excludedUserIds).to("excludedUserIds")
                .bind(limit).to("limit")
                .fetch()
                .all()
                .stream()
                .map(this::toRecommendedFollowCandidate)
                .toList();
    }

    private RecommendedFollowCandidate toRecommendedFollowCandidate(Map<String, Object> row) {
        var userIdValue = row.get("userId");
        var commonConnectionsValue = row.get("commonConnections");
        if (userIdValue == null) {
            throw new IllegalStateException("Neo4j recommended user query returned a null userId");
        }
        if (!(commonConnectionsValue instanceof Number commonConnections)) {
            throw new IllegalStateException("Neo4j recommended user query returned an invalid commonConnections value");
        }

        return new RecommendedFollowCandidate(
                UUID.fromString(userIdValue.toString()),
                commonConnections.longValue()
        );
    }
}
