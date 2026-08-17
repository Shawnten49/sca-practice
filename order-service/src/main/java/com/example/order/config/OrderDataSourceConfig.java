package com.example.order.config;

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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 双数据源：BASE（分布式事务） + LOCAL（本地事务）。
 *
 * <p>ShardingSphere 5.4+ 的事务模式是数据源级全局配置（无按方法切换的注解），
 * 因此把「需要 Seata 全局事务」和「只需本地事务」拆到两个 ShardingSphere 数据源：
 * <ul>
 *   <li>{@code dataSource}（@Primary）+ {@code seataTransactionManager}（@Primary）：
 *       shardingsphere.yaml，transaction.defaultType=BASE。业务方法写
 *       {@code @Transactional("seataTransactionManager")} 即明确走 Seata 全局事务；
 *       不带名字的 {@code @Transactional} 也落到它（@Primary 默认）。</li>
 *   <li>{@code localDataSource} + {@code localTransactionManager}：
 *       shardingsphere-local.yaml，transaction.defaultType=LOCAL。业务方法写
 *       {@code @Transactional("localTransactionManager")} 明确只走物理库本地事务，
 *       不产生 Seata 全局事务（分片路由照常）。</li>
 * </ul>
 * 两个数据源分片规则完全一致（orders → orders_0~3，id % 4），只是事务语义不同。
 *
 * <p>Mapper 按包绑定 SqlSessionFactory（@MapperScan）：
 * <ul>
 *   <li>{@code com.example.order.mapper} → {@code sqlSessionFactory}（BASE 数据源）；</li>
 *   <li>{@code com.example.order.dao} → {@code localSqlSessionFactory}（LOCAL 数据源）。</li>
 * </ul>
 * 新增本地 mapper 时：接口放进 {@code com.example.order.dao}、XML 放进
 * {@code src/main/resources/mapper-local/}，即自动注册，无需再写任何配置。
 *
 * <p>事务注解约定（让开发者在注解上直接感知是否使用 Seata）：
 * <pre>
 *   @Transactional("seataTransactionManager")  // Seata 全局事务，配合 OrderMapper（BASE 数据源）
 *   @Transactional("localTransactionManager")  // 本地事务，配合 LocalOrderMapper（LOCAL 数据源）
 * </pre>
 */
@Configuration
@MapperScan(basePackages = "com.example.order.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.example.order.dao", sqlSessionFactoryRef = "localSqlSessionFactory")
public class OrderDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource() {
        return createShardingSphereDataSource("shardingsphere.yaml");
    }

    @Bean
    public DataSource localDataSource() {
        return createShardingSphereDataSource("shardingsphere-local.yaml");
    }

    /**
     * BASE 数据源的默认 SqlSessionFactory（@Primary）。
     *
     * <p>MyBatis-Plus 自动装配的工厂带 @ConditionalOnMissingBean(SqlSessionFactory.class)，
     * 一旦出现任何同类型 Bean 就会整体退避，因此这里显式接管工厂；
     * 配置继续从 MybatisPlusProperties（application.yml 的 mybatis-plus.*）读取，
     * 与自动装配行为保持一致。
     */
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

    /** LOCAL 数据源的 SqlSessionFactory：只加载 mapper-local/ 下的 XML，供 com.example.order.dao 使用。 */
    @Bean
    public SqlSessionFactory localSqlSessionFactory(@Qualifier("localDataSource") DataSource localDataSource,
                                                    ApplicationContext applicationContext) throws Exception {
        return buildLocalSqlSessionFactory(localDataSource, applicationContext);
    }

    static SqlSessionFactory buildLocalSqlSessionFactory(DataSource localDataSource,
                                                         ApplicationContext applicationContext) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(localDataSource);
        factoryBean.setVfs(SpringBootVFS.class);
        if (null != applicationContext) {
            factoryBean.setApplicationContext(applicationContext);
        }
        // 放在 mapper-local/ 而不是 mapper/：避免被 BASE 工厂的 classpath*:mapper/**/*.xml 扫描到
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper-local/*.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    @Bean
    @Primary
    public PlatformTransactionManager seataTransactionManager(
            @Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public PlatformTransactionManager localTransactionManager(
            @Qualifier("localDataSource") DataSource localDataSource) {
        return new DataSourceTransactionManager(localDataSource);
    }

    private DataSource createShardingSphereDataSource(String yaml) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(ShardingSphereDriver.class.getName());
        dataSource.setJdbcUrl("jdbc:shardingsphere:classpath:" + yaml);
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(10_000);
        return dataSource;
    }
}
