package com.app.postcommandservice.collab.infrastructure.mapper;

import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import org.springframework.stereotype.Component;

@Component
public class CollabMapper {

    public CollabEntity toEntity(Collab collab) {
        return new CollabEntity(
                collab.getId(),
                collab.getTitle().value(),
                collab.getCreatedBy().value(),
                collab.getCollabStatus(),
                collab.getCreatedAt()
        );
    }

    public Collab toDomain(CollabEntity entity) {
        return new Collab(
                entity.getId(),
                new CollabTitle(entity.getTitle()),
                new UserId(entity.getCreatedBy()),
                entity.getCollabStatus(),
                entity.getCreatedAt()
        );
    }
}
