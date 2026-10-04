package com.app.postcommandservice.shared.infrastructure.shedlock;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires ShedLock's {@code JdbcTemplateLockProvider} over this service's own {@code DataSource}
 * so {@code @SchedulerLock}-annotated jobs (task 39's media-cleanup job today) run on exactly
 * one application instance at a time, backed by the {@code shedlock} table ({@link
 * ShedLockEntity}).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
