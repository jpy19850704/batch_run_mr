package com.zcyh.mr.springboot.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zcyh.mr.scenario.ScenarioService;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scenario 业务数据源配置。
 */
@Configuration
public class ScenarioDataSourceConfig {

    @Bean(name = "scenarioDataSourceProperties")
    @ConfigurationProperties(prefix = "mr.scenario.datasource")
    @ConditionalOnProperty(prefix = "mr.scenario.service", name = "enabled", havingValue = "true")
    public DataSourceProperties scenarioDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "scenarioDataSource")
    @ConditionalOnBean(name = "scenarioDataSourceProperties")
    @ConfigurationProperties(prefix = "mr.scenario.datasource.hikari")
    public DataSource scenarioDataSource(
            @Qualifier("scenarioDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "scenarioSqlSessionFactory")
    @ConditionalOnBean(name = "scenarioDataSource")
    public SqlSessionFactory scenarioSqlSessionFactory(
            @Qualifier("scenarioDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(resolveScenarioMapperLocations());
        return factoryBean.getObject();
    }

    @Bean(name = "scenarioSqlSessionTemplate")
    @ConditionalOnBean(name = "scenarioSqlSessionFactory")
    public SqlSessionTemplate scenarioSqlSessionTemplate(
            @Qualifier("scenarioSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    @ConditionalOnBean(name = "scenarioSqlSessionTemplate")
    public ScenarioMapper scenarioMapper(
            @Qualifier("scenarioSqlSessionTemplate") SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(ScenarioMapper.class);
    }

    @Bean
    @ConditionalOnBean(ScenarioMapper.class)
    public ScenarioRequestAssembler scenarioRequestAssembler(ScenarioMapper scenarioMapper) {
        return new ScenarioRequestAssembler(scenarioMapper);
    }

    @Bean
    @ConditionalOnBean(ScenarioRequestAssembler.class)
    public ScenarioService.ScenarioRequestLoader scenarioRequestLoader(
            ScenarioRequestAssembler scenarioRequestAssembler) {
        return (scenarioIdList, valuationDate, user) -> scenarioRequestAssembler.build(
                scenarioIdList,
                valuationDate,
                user,
                "mr-app");
    }

    @Bean(name = "scenarioExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "mr.scenario.service", name = "enabled", havingValue = "true")
    public ExecutorService scenarioExecutor(
            @Value("${mr.scenario.executor.core-size:4}") int coreSize,
            @Value("${mr.scenario.executor.max-size:8}") int maxSize,
            @Value("${mr.scenario.executor.queue-capacity:1024}") int queueCapacity) {
        int normalizedCoreSize = Math.max(1, coreSize);
        int normalizedMaxSize = Math.max(normalizedCoreSize, maxSize);
        int normalizedQueueCapacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                normalizedCoreSize,
                normalizedMaxSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(normalizedQueueCapacity),
                namedThreadFactory("scenario-exec-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    @ConditionalOnBean({ScenarioService.ScenarioRequestLoader.class, ExecutorService.class})
    public ScenarioService scenarioService(
            @Qualifier("scenarioExecutor") ExecutorService scenarioExecutor) {
        return new ScenarioService(scenarioExecutor);
    }

    private Resource[] resolveScenarioMapperLocations() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return resolver.getResources("classpath*:mapper/ScenarioMapper.xml");
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + seq.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
