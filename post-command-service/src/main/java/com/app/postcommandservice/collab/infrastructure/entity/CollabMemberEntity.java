package com.app.postcommandservice.collab.infrastructure.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;

@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "collab_members")
public class CollabMemberEntity {

    @EmbeddedId
    private CollabMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "collab_member_status", nullable = false)
    private CollabMemberStatus collabMemberStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollabMemberRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
