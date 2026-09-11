package com.app.postcommandservice.post.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;

@Embeddable
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class PostInfoEmbeddable {

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false)
    private PostType postType;

    @ElementCollection
    @OnDelete(action = OnDeleteAction.CASCADE)
    @CollectionTable(name = "post_tagged_users", joinColumns = @jakarta.persistence.JoinColumn(
            name = "post_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_post_tagged_users_post")))
    @Column(name = "username", nullable = false)
    @Builder.Default
    private List<String> taggedUsers = new ArrayList<>();

    @ElementCollection
    @OnDelete(action = OnDeleteAction.CASCADE)
    @CollectionTable(name = "post_tags", joinColumns = @jakarta.persistence.JoinColumn(
            name = "post_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_post_tags_post")))
    @Column(name = "tag_value", nullable = false)
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
