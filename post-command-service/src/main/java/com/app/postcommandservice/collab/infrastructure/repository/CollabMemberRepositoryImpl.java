package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabMemberMapper;

@Repository
@RequiredArgsConstructor
public class CollabMemberRepositoryImpl implements CollabMemberRepository {

    private final CollabMemberJpaRepository collabMemberJpaRepository;
    private final CollabMemberMapper collabMemberMapper;

    @Override
    public CollabMember save(CollabMember collabMember) {
        return collabMemberMapper.toDomain(collabMemberJpaRepository.save(collabMemberMapper.toEntity(collabMember)));
    }

    @Override
    public Optional<CollabMember> findByCollabIdAndUserId(UUID collabId, UUID userId) {
        return collabMemberJpaRepository.findByIdCollabIdAndIdUserId(collabId, userId)
                .map(collabMemberMapper::toDomain);
    }
}
