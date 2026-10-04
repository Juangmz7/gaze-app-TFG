package com.app.postcommandservice.post.infrastructure.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(
        name = "post_media",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_media_post_id_media_order",
                columnNames = {"post_id", "media_order"}
        )
)
public class PostMediaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_post_media_post"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PostEntity post;

    @Column(nullable = false)
    private String url;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    @Column
    private Integer duration;

    @ElementCollection
    @CollectionTable(name = "post_media_tagged_users", joinColumns = @JoinColumn(name = "post_media_id"))
    @Column(name = "username", nullable = false)
    @Builder.Default
    private List<String> taggedUsers = new ArrayList<>();

    @Column(name = "media_order", nullable = false)
    private Integer mediaOrder;

    /**
     * BCrypt hash of the most recently issued upload SAS url for {@link #url} (task 38). Never
     * the plain SAS, which is never persisted. {@code null} until a SAS has been issued via the
     * refresh-upload-urls endpoint.
     */
    @Column(name = "upload_sas_hash")
    private String uploadSasHash;

    @Column(name = "upload_sas_expires_at")
    private Instant uploadSasExpiresAt;

    /**
     * BCrypt hash of the most recently issued upload SAS url for {@link #thumbnailUrl}, used only
     * for {@code VIDEO} media (task 38). {@code null} for {@code IMAGE} media and for any
     * {@code VIDEO} media that has not had its thumbnail SAS refreshed yet.
     */
    @Column(name = "thumbnail_sas_hash")
    private String thumbnailSasHash;

    @Column(name = "thumbnail_sas_expires_at")
    private Instant thumbnailSasExpiresAt;
}
