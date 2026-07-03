package com.app.socialservice.shared.infrastructure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import javax.sql.DataSource;

@Configuration
public class DatasourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource hikariDataSource(
            DataSourceProperties properties,
            ObjectProvider<JdbcConnectionDetails> connectionDetails) {
        JdbcConnectionDetails details = connectionDetails.getIfAvailable();
        if (details != null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(details.getJdbcUrl());
            ds.setUsername(details.getUsername());
            ds.setPassword(details.getPassword());
            if (details.getDriverClassName() != null) {
                ds.setDriverClassName(details.getDriverClassName());
            }
            return ds;
        }
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikari) {
        return new LazyConnectionDataSourceProxy(hikari);
    }
}
