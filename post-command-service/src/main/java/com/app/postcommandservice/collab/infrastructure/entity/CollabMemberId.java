package com.app.postcommandservice.collab.infrastructure.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CollabMemberId implements Serializable {

    @Column(name = "collab_id", nullable = false, updatable = false)
    private UUID collabId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
}
