package com.app.postcommandservice.collab.application.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.postcommandservice.collab.domain.model.Collab;

public interface CollabRepository {

    Collab save(Collab collab);

    Optional<Collab> findById(UUID collabId);
}
