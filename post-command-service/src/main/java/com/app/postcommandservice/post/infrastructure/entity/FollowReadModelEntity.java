package com.app.postcommandservice.post.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_follows")
@IdClass(FollowReadModelId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowReadModelEntity {
    @Id
    @Column(name = "follower_id")
    private UUID followerId;

    @Id
    @Column(name = "followed_id")
    private UUID followedId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
