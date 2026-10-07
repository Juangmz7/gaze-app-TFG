package com.app.postcommandservice.shared.infrastructure.repository;


import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Relay-side operations each run in their own short transaction
 * (REQUIRES_NEW): the claim is committed before publishing, so no database
 * transaction stays open while waiting for the broker confirm. REQUIRES_NEW is
 * also required because the immediate sender runs in the AFTER_COMMIT phase of
 * the business transaction.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Atomically claims the oldest PENDING row, or a PROCESSING row whose lock
     * expired (its relay died mid-publish). SKIP LOCKED lets several instances
     * claim concurrently without ever getting the same row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
             WHERE id = (
                   SELECT id FROM outbox_event
                    WHERE status = 'PENDING'
                       OR (status = 'PROCESSING' AND locked_at < :lockedBefore)
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    Optional<OutboxEvent> claimNext(@Param("lockedBefore") Instant lockedBefore);

    /** Claims one specific row, only if it is still PENDING (immediate send after commit). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
             WHERE id = :id AND status = 'PENDING'
            RETURNING *
            """, nativeQuery = true)
    Optional<OutboxEvent> claimIfPending(@Param("id") UUID id);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE outbox_event
               SET status = 'PROCESSED', processed_at = now(), locked_at = NULL, last_error = NULL
             WHERE id = :id
            """, nativeQuery = true)
    int markProcessed(@Param("id") UUID id);

    /** Releases a failed claim: back to PENDING, or FAILED once attempts are exhausted. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE outbox_event
               SET status = :status, last_error = :lastError, locked_at = NULL
             WHERE id = :id
            """, nativeQuery = true)
    int markFailedAttempt(@Param("id") UUID id,
                          @Param("status") String status,
                          @Param("lastError") String lastError);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "DELETE FROM outbox_event WHERE status = 'PROCESSED' AND processed_at < :processedBefore",
            nativeQuery = true)
    int deleteProcessedBefore(@Param("processedBefore") Instant processedBefore);
}
