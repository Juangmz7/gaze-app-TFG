package com.app.postcommandservice.collab.infrastructure.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.infrastructure.entity.CollabMemberId;
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
        return collabMemberJpaRepository.findById(new CollabMemberId(collabId, userId))
                .map(collabMemberMapper::toDomain);
    }

    @Override
    public Set<UUID> findUserIdsByCollabId(UUID collabId) {
        return Set.copyOf(collabMemberJpaRepository.findUserIdsByCollabId(collabId));
    }

    @Override
    public boolean acceptPendingMember(UUID collabId, UUID userId) {
        return collabMemberJpaRepository.acceptPendingMember(
                collabId,
                userId,
                CollabMemberStatus.PENDING,
                CollabMemberStatus.ACCEPTED
        ) == 1;
    }

    @Override
    public boolean rejectPendingMember(UUID collabId, UUID userId) {
        return collabMemberJpaRepository.rejectPendingMember(
                collabId,
                userId,
                CollabMemberStatus.PENDING,
                CollabMemberStatus.REJECTED
        ) == 1;
    }

    @Override
    public boolean deletePendingMember(UUID collabId, UUID userId) {
        return collabMemberJpaRepository.deletePendingMember(
                collabId,
                userId,
                CollabMemberStatus.PENDING,
                CollabMemberStatus.DELETED
        ) == 1;
    }
}
