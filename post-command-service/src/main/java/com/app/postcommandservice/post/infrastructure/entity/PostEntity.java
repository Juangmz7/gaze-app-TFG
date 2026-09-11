package com.app.postcommandservice.post.infrastructure.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.FetchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

    @Embedded
    private PostInfoEmbeddable postInfo;

    @OneToMany(mappedBy = "post", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @OrderBy("order ASC")
    @Builder.Default
    private List<PostMediaEntity> media = new ArrayList<>();

    public void replaceMedia(List<PostMediaEntity> replacement) {
        media.clear();
        replacement.forEach(item -> {
            item.setPost(this);
            media.add(item);
        });
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public String getTitle() {
        return postInfo.getTitle();
    }

    public String getDescription() {
        return postInfo.getDescription();
    }

    public com.app.postcommandservice.post.domain.model.valueobj.PostType getPostType() {
        return postInfo.getPostType();
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        synchronizeMediaOwnership();
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        synchronizeMediaOwnership();
        updatedAt = Instant.now();
    }

    private void synchronizeMediaOwnership() {
        media.forEach(item -> item.setPost(this));
    }
}
