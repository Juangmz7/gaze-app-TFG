package com.app.socialservice.shared.infrastructure.datasource;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableNeo4jRepositories(
        basePackages = "com.app.socialservice",
        transactionManagerRef = "neo4jTransactionManager",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JpaRepository.class
        )
)
public class Neo4jConfig {

        @Bean("neo4jTransactionManager")
        public PlatformTransactionManager neo4jTransactionManager(
                Driver driver,
                DatabaseSelectionProvider databaseSelectionProvider) {
                return new Neo4jTransactionManager(driver, databaseSelectionProvider);
        }
}
