package com.zcyh.mr.springboot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Engine 输入/任务库数据源配置。
 */
@Configuration
public class EngineDbDataSourceConfig {

    @Bean(name = "engineDbDataSourceProperties")
    @ConfigurationProperties(prefix = "enginedb.datasource")
    public DataSourceProperties engineDbDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "engineDbDataSource")
    @ConditionalOnBean(name = "engineDbDataSourceProperties")
    @ConfigurationProperties(prefix = "enginedb.datasource.hikari")
    public DataSource engineDbDataSource(
            @Qualifier("engineDbDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
