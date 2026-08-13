package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.postcommandservice.post.infrastructure.entity.UserReadModelEntity;

public interface UserReadModelJpaRepository extends JpaRepository<UserReadModelEntity, UUID> {

    List<UserReadModelEntity> findByUsernameIn(Collection<String> usernames);
}
