package com.app.postcommandservice.view.infrastructure.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;

@Entity
@Table(
        name = "post_views",
        indexes = {
                @Index(name = "idx_post_views_post_user", columnList = "post_id,user_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostViewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PostViewSource source;

    @Column(nullable = false, updatable = false)
    private int feedPosition;

    @Column(nullable = false, updatable = false)
    private int durationMs;

    @Column(nullable = false, updatable = false)
    private int timeWatchedMs;

    @Column(nullable = false, updatable = false)
    private int completionPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PostViewExitReason exitReason;

    @Column(nullable = false, updatable = false)
    private Instant serverTimestamp;

    @Column(nullable = false, updatable = false)
    private int replayCount;
}
