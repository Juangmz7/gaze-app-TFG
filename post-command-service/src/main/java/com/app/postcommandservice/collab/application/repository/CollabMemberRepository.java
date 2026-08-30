package com.app.postcommandservice.collab.application.repository;

import com.app.postcommandservice.collab.domain.model.CollabMember;

public interface CollabMemberRepository {

    CollabMember save(CollabMember collabMember);
}
