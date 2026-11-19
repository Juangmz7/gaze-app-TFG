package com.app.postcommandservice.shared.infrastructure.shedlock;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapping of ShedLock's {@code shedlock} table. It is never read or written through JPA (ShedLock
 * owns the rows through JDBC); it exists so Hibernate creates the table in tests
 * ({@code ddl-auto=create-drop}) and validates it in prod against
 * {@code db/schema/post-command-service-prod.sql}, like every other table of this service.
 *
 * <p>The timestamps are {@code TIMESTAMP} without time zone, as ShedLock expects: with
 * {@code usingDbTime()} it writes and compares UTC values from the database clock.</p>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = ShedLockEntity.TABLE_NAME)
public class ShedLockEntity {

    public static final String TABLE_NAME = "shedlock";

    @Id
    @Column(length = 64, nullable = false)
    private String name;

    @Column(name = "lock_until", nullable = false)
    private LocalDateTime lockUntil;

    @Column(name = "locked_at", nullable = false)
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", nullable = false)
    private String lockedBy;
}
