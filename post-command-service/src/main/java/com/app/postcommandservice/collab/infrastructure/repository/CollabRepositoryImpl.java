package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabMapper;

@Repository
@RequiredArgsConstructor
public class CollabRepositoryImpl implements CollabRepository {

    private final CollabJpaRepository collabJpaRepository;
    private final CollabMapper collabMapper;

    @Override
    public Collab save(Collab collab) {
        return collabMapper.toDomain(collabJpaRepository.save(collabMapper.toEntity(collab)));
    }

    @Override
    public Optional<Collab> findById(UUID collabId) {
        return collabJpaRepository.findById(collabId).map(collabMapper::toDomain);
    }
}
