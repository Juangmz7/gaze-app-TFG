package com.app.postcommandservice.collab.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberEntity;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class CollabMemberMapper {

    public CollabMemberEntity toEntity(CollabMember collabMember) {
        return new CollabMemberEntity(
                new CollabMemberId(collabMember.getCollabId(), collabMember.getUserId().value()),
                collabMember.getCollabMemberStatus(),
                collabMember.getRole(),
                collabMember.getCreatedAt()
        );
    }

    public CollabMember toDomain(CollabMemberEntity entity) {
        return new CollabMember(
                entity.getId().getCollabId(),
                new UserId(entity.getId().getUserId()),
                entity.getCollabMemberStatus(),
                entity.getRole(),
                entity.getCreatedAt()
        );
    }
}
