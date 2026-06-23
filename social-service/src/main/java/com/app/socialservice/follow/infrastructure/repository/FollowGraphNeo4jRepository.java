package com.app.socialservice.follow.infrastructure.repository;

import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FollowGraphNeo4jRepository implements FollowGraphRepository {

    private final Neo4jClient neo4jClient;

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
}
