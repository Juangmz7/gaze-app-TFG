package com.app.socialservice.block.infrastructure.repository;

import com.app.socialservice.block.infrastructure.entity.BlockEntity;
import com.app.socialservice.block.infrastructure.entity.BlockEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaBlockRepository extends JpaRepository<BlockEntity, BlockEntityId> {
}
