package com.zcyh.mr.springboot.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * JdbcTemplate 与事务管理器配置。
 * engineDb 数据源用于输入与任务状态（MySQL），engineResultDb 数据源用于结果输出（Doris）。
 */
@Configuration
public class JdbcTemplateConfig {

    @Bean(name = "engineResultDbDataSourceProperties")
    @ConfigurationProperties(prefix = "engineresultdb.datasource")
    public DataSourceProperties engineResultDbDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "engineResultDbDataSource")
    @ConfigurationProperties(prefix = "engineresultdb.datasource.hikari")
    public DataSource engineResultDbDataSource(
            @Qualifier("engineResultDbDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "engineResultDbJdbcTemplate")
    public JdbcTemplate engineResultDbJdbcTemplate(
            @Qualifier("engineResultDbDataSource") DataSource engineResultDbDataSource) {
        return new JdbcTemplate(engineResultDbDataSource);
    }

    @Bean(name = "engineDbJdbcTemplate")
    @Primary
    public JdbcTemplate engineDbJdbcTemplate(@Qualifier("engineDbDataSource") DataSource engineDbDataSource) {
        return new JdbcTemplate(engineDbDataSource);
    }

    @Bean(name = "engineDbTransactionManager")
    @Primary
    public PlatformTransactionManager engineDbTransactionManager(
            @Qualifier("engineDbDataSource") DataSource engineDbDataSource) {
        return new DataSourceTransactionManager(engineDbDataSource);
    }

    @Bean(name = "engineResultDbTransactionManager")
    public PlatformTransactionManager engineResultDbTransactionManager(
            @Qualifier("engineResultDbDataSource") DataSource engineResultDbDataSource) {
        return new DataSourceTransactionManager(engineResultDbDataSource);
    }

    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("engineDbTransactionManager") PlatformTransactionManager engineDbTransactionManager) {
        return engineDbTransactionManager;
    }
}
