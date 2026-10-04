package com.app.postcommandservice.shared.infrastructure.shedlock;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps the {@code shedlock} table that {@code net.javacrumbs.shedlock}'s {@code
 * JdbcTemplateLockProvider} reads/writes with plain JDBC (task 39). This project has no
 * Flyway/Liquibase migration tool: Hibernate's own {@code ddl-auto} (create-drop in test,
 * update in dev, validate in prod, backed by the hand-maintained {@code
 * post-command-service-prod.sql} mirror) is the only schema-management mechanism, so this
 * table is modeled as a plain JPA entity purely to let Hibernate create/validate it exactly
 * like every other table — nothing in this codebase ever persists a {@code ShedLockEntity}
 * through a repository; ShedLock owns all reads/writes to this table itself.
 */
@Entity
@Table(name = "shedlock")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShedLockEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String name;

    @Column(name = "lock_until", nullable = false)
    private Instant lockUntil;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "locked_by", nullable = false)
    private String lockedBy;
}
