package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.collab.infrastructure.entity.CollabEntity;

public interface CollabJpaRepository extends JpaRepository<CollabEntity, UUID> {
}
