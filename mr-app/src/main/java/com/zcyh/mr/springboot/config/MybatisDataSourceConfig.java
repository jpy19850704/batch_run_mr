package com.zcyh.mr.springboot.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * MyBatis 双数据源配置。
 */
@Configuration
@MapperScans({
        @MapperScan(
                basePackages = "com.zcyh.mr.springboot.mybatis.enginedb",
                sqlSessionTemplateRef = "engineDbSqlSessionTemplate"
        ),
        @MapperScan(
                basePackages = "com.zcyh.mr.springboot.mybatis.engineresultdb",
                sqlSessionTemplateRef = "engineResultDbSqlSessionTemplate"
        )
})
public class MybatisDataSourceConfig {

    @Bean(name = "engineDbSqlSessionFactory")
    public SqlSessionFactory engineDbSqlSessionFactory(
            @Qualifier("engineDbDataSource") DataSource dataSource) throws Exception {
        return createSqlSessionFactory(dataSource);
    }

    @Bean(name = "engineDbSqlSessionTemplate")
    public SqlSessionTemplate engineDbSqlSessionTemplate(
            @Qualifier("engineDbSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean(name = "engineResultDbSqlSessionFactory")
    public SqlSessionFactory engineResultDbSqlSessionFactory(
            @Qualifier("engineResultDbDataSource") DataSource dataSource) throws Exception {
        return createSqlSessionFactory(dataSource);
    }

    @Bean(name = "engineResultDbSqlSessionTemplate")
    public SqlSessionTemplate engineResultDbSqlSessionTemplate(
            @Qualifier("engineResultDbSqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    private static SqlSessionFactory createSqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }
}
