package com.app.socialservice.user.infrastructure.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Counters of a user that no table of this service can count: the posts live in
 * post-command-service and only their events arrive. Written only through the atomic
 * upserts of {@link com.app.socialservice.user.infrastructure.repository.JpaUserStatsRepository},
 * in the same transaction as the processed event, never loaded and saved back.
 * Followers and following are counted from {@code follows} instead.
 */
@Getter
@Entity
@Table(name = "user_stats")
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "post_count", nullable = false, columnDefinition = "bigint default 0")
    private long postCount;
}
