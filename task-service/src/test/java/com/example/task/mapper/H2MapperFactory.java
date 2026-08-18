package com.example.task.mapper;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H2 测试支撑：为 Mapper 单测构建独立的 MyBatis SqlSessionFactory。
 * 每个测试使用独立的内存库实例，避免表名互相干扰。
 */
final class H2MapperFactory {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private H2MapperFactory() {
    }

    static SqlSessionFactory create(String... ddl) throws Exception {
        String dbName = "task_test_" + DB_SEQ.incrementAndGet();
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.execute(sql);
            }
        }

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/*.xml"));
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }
}
