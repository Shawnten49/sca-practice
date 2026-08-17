package com.example.user.sharding.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.SpringBootVFS;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.ShardingSphereDriver;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 双机制总装配：默认机制（不分表）+ Sharding 机制（分表）。
 *
 * <p>参考 order-service 的双工厂模式。为什么默认数据源/默认工厂要显式接管：
 * <ul>
 *   <li>MyBatis-Plus 自动装配的 {@code sqlSessionFactory} 是
 *       {@code @ConditionalOnMissingBean(SqlSessionFactory.class)}（按类型），出现第二个工厂 Bean 就整体退避；</li>
 *   <li>Boot 自动装配的 {@code dataSource} 不是 {@code @Primary}，出现第二个 DataSource Bean 后
 *       MP 的 {@code @ConditionalOnSingleCandidate(DataSource.class)} 也会失效。</li>
 * </ul>
 * 因此这里同时定义 {@code dataSource}/{@code sqlSessionFactory}（@Primary，配置仍读
 * {@code spring.datasource.*} 与 {@code mybatis-plus.*}，行为与自动装配一致）和
 * {@code shardingDataSource}/{@code shardingSqlSessionFactory}（只加载 {@code sharding-mapper/*.xml}）。
 * 默认机制的 mapper/XML/SQL/事务行为零变化。
 */
@Configuration
@MapperScan(basePackages = "com.example.user.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.example.user.sharding.mapper", sqlSessionFactoryRef = "shardingSqlSessionFactory")
public class ShardingSphereConfig {

    // ========== 默认机制（行为与自动装配一致） ==========

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(10_000);
        return dataSource;
    }

    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource,
                                               MybatisPlusProperties mybatisPlusProperties,
                                               ApplicationContext applicationContext) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setVfs(SpringBootVFS.class);
        factoryBean.setApplicationContext(applicationContext);
        factoryBean.setMapperLocations(mybatisPlusProperties.resolveMapperLocations());
        // MP 3.5.17：CoreConfiguration.applyTo() 把 yml 的 mybatis-plus.configuration.* 应用到 MybatisConfiguration
        MybatisConfiguration configuration = new MybatisConfiguration();
        mybatisPlusProperties.getConfiguration().applyTo(configuration);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(mybatisPlusProperties.getGlobalConfig());
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    // ========== Sharding 机制 ==========

    @Bean
    public DataSource shardingDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(ShardingSphereDriver.class.getName());
        dataSource.setJdbcUrl("jdbc:shardingsphere:classpath:shardingsphere.yaml");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean
    public SqlSessionFactory shardingSqlSessionFactory(@Qualifier("shardingDataSource") DataSource shardingDataSource,
                                                       ApplicationContext applicationContext) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(shardingDataSource);
        factoryBean.setVfs(SpringBootVFS.class);
        factoryBean.setApplicationContext(applicationContext);
        // 只加载 sharding-mapper/*.xml；默认工厂的 classpath*:mapper/**/*.xml 不会扫到它
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:sharding-mapper/*.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }
}
