package com.example.user.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.user.domain.SyncLog;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** SyncLogMapper INSERT IGNORE 幂等去重集成测试（H2 内存库，MySQL 模式）。 */
class SyncLogMapperXmlTest {

    private SqlSession sqlSession;
    private SyncLogMapper syncLogMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sync_log_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sync_log");
            st.execute("CREATE TABLE sync_log (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "log_file_name VARCHAR(64) NOT NULL," +
                    "log_file_offset BIGINT NOT NULL," +
                    "row_key VARCHAR(128) NOT NULL DEFAULT ''," +
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uk_sync_log_position (log_file_name, log_file_offset, row_key))");
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/SyncLogMapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        syncLogMapper = sqlSession.getMapper(SyncLogMapper.class);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void insertIgnoreFirstPositionClaims() {
        assertThat(insert("mysql-bin.000001", 123L)).isEqualTo(1);
    }

    @Test
    void insertIgnoreDuplicatePositionReturnsZero() {
        insert("mysql-bin.000001", 123L);

        // 同一位点重复投递：INSERT IGNORE 返回 0（已处理过）
        assertThat(insert("mysql-bin.000001", 123L)).isZero();
    }

    @Test
    void insertIgnoreDifferentPositionAllowed() {
        insert("mysql-bin.000001", 123L);

        assertThat(insert("mysql-bin.000001", 124L)).isEqualTo(1);
        assertThat(insert("mysql-bin.000002", 1L)).isEqualTo(1);
    }

    @Test
    void samePositionDifferentRowKeyAllowed() {
        // 同一条多行 SQL：位点相同、行级 key 不同，必须都能抢占
        assertThat(insert("mysql-bin.000001", 123L, "1")).isEqualTo(1);
        assertThat(insert("mysql-bin.000001", 123L, "2")).isEqualTo(1);

        // 同一位点 + 同一行重复投递 → 去重
        assertThat(insert("mysql-bin.000001", 123L, "1")).isZero();
    }

    private int insert(String file, long offset) {
        return insert(file, offset, "");
    }

    private int insert(String file, long offset, String rowKey) {
        return syncLogMapper.insertIgnore(SyncLog.builder()
                .logFileName(file)
                .logFileOffset(offset)
                .rowKey(rowKey)
                .build());
    }
}
