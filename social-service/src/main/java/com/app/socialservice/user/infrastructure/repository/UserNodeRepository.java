package com.app.socialservice.user.infrastructure.repository;

import com.app.socialservice.user.infrastructure.entity.UserNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserNodeRepository extends Neo4jRepository<UserNode, UUID> {
}
