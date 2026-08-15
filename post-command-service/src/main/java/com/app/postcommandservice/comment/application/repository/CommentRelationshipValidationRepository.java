package com.app.postcommandservice.comment.application.repository;

import java.util.UUID;

public interface CommentRelationshipValidationRepository {

    boolean existsBlockRelationship(UUID requesterUserId, UUID targetUserId);
}
