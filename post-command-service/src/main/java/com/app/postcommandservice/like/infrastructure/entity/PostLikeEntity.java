package com.app.postcommandservice.like.infrastructure.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.app.postcommandservice.like.domain.model.PostLikeSource;

@Entity
@Table(name = "post_likes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostLikeEntity {

    @EmbeddedId
    private PostLikeId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PostLikeSource source;

    @Column(nullable = false, updatable = false)
    private int feedPosition;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
