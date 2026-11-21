package com.app.postcommandservice.shared.infrastructure.shedlock;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed lock for {@code @Scheduled} jobs that must run on a single instance per interval
 * (task 58: the media cleanup job). Locks live in the {@code shedlock} table of this service's
 * own database ({@link ShedLockEntity}) and use the database clock, so instances with skewed
 * clocks still agree on when a lock expires.
 *
 * <p>The default {@code lockAtMostFor} only applies to jobs that do not set their own; it bounds
 * how long a lock survives an instance that dies while holding it.</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(ShedLockEntity.TABLE_NAME)
                .usingDbTime()
                .build());
    }
}
