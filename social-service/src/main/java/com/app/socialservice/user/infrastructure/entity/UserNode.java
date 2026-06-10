package com.app.socialservice.user.entity;

import lombok.*;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.util.UUID;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Node("User")
public class UserNode {
    @Id
    private UUID id; // UUID from user postgres relation
}
