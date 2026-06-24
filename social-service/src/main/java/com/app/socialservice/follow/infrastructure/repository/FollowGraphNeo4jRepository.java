package com.app.socialservice.follow.infrastructure.repository;

import java.util.UUID;

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
}
