package com.zcyh.mr.springboot.config;

import com.zcyh.mr.springboot.calendar.mapper.CalendarMapper;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine 输入库统一配置。
 * 统一管理 input 数据源、MyBatis 会话和输入侧 Mapper。
 */
@Configuration
public class EngineInputDataSourceConfig {

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

    @Bean(name = "engineDbSqlSessionFactory")
    @ConditionalOnBean(name = "engineDbDataSource")
    public SqlSessionFactory engineDbSqlSessionFactory(
            @Qualifier("engineDbDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(resolveEngineInputMapperLocations());
        return factoryBean.getObject();
    }

    @Bean(name = "engineDbSqlSessionTemplate")
    @ConditionalOnBean(name = "engineDbSqlSessionFactory")
    public SqlSessionTemplate engineDbSqlSessionTemplate(
            @Qualifier("engineDbSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    @ConditionalOnBean(name = "engineDbSqlSessionTemplate")
    public ScenarioMapper scenarioMapper(
            @Qualifier("engineDbSqlSessionTemplate") SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(ScenarioMapper.class);
    }

    @Bean
    @ConditionalOnBean(name = "engineDbSqlSessionTemplate")
    public CalendarMapper calendarMapper(
            @Qualifier("engineDbSqlSessionTemplate") SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(CalendarMapper.class);
    }

    private Resource[] resolveEngineInputMapperLocations() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<Resource>();
        Resource[] scenarioMappers = resolver.getResources("classpath*:mapper/ScenarioMapper.xml");
        Resource[] calendarMappers = resolver.getResources("classpath*:mapper/CalendarMapper.xml");
        for (Resource mapper : scenarioMappers) {
            resources.add(mapper);
        }
        for (Resource mapper : calendarMappers) {
            resources.add(mapper);
        }
        return resources.toArray(new Resource[0]);
    }
}
