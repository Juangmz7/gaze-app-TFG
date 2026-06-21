package com.app.socialservice.shared.infrastructure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
    public HikariDataSource hikariDataSource(JdbcConnectionDetails connectionDetails) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(connectionDetails.getJdbcUrl());
        ds.setUsername(connectionDetails.getUsername());
        ds.setPassword(connectionDetails.getPassword());
        ds.setDriverClassName(connectionDetails.getDriverClassName());
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikari) {
        return new LazyConnectionDataSourceProxy(hikari);
    }
}