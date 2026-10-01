package com.app.postcommandservice.post.infrastructure.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowReadModelId implements Serializable {
    private UUID followerId;
    private UUID followedId;
}
