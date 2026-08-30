package com.app.postcommandservice.post.infrastructure.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.FetchType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "collab_id")
    private UUID collabId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collab_id", foreignKey = @ForeignKey(name = "fk_posts_collab"), insertable = false, updatable = false)
    private CollabEntity collab;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false)
    private com.app.postcommandservice.post.domain.model.valueobj.PostType postType;

    @Column(nullable = false, length = 4000)
    private String description;

    @ElementCollection
    @CollectionTable(name = "post_tagged_users", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "username", nullable = false)
    @Builder.Default
    private List<String> taggedUsers = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "tag_value", nullable = false)
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
