package com.example.order.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.entity.Order;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShardingSphere 分片路由集成测试（H2 内存库 + 4 张分片表，不启动 Spring 上下文）。
 *
 * <p>验证与生产一致的分片规则（orders 逻辑表 → orders_0~3，id % 4）：
 * 自定义 XML 与 BaseMapper 的 SQL 都只写逻辑表名，由 ShardingSphere 改写为物理表。
 */
class ShardingRoutingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:order_shard_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private JdbcDataSource rawDataSource;
    private SqlSession sqlSession;
    private OrderMapper orderMapper;
    private DataSource shardingDataSource;

    @BeforeEach
    void setUp() throws Exception {
        rawDataSource = new JdbcDataSource();
        rawDataSource.setURL(H2_URL);
        rawDataSource.setUser("sa");
        createShardTables();

        byte[] yamlBytes = new ClassPathResource("shardingsphere-test.yaml")
                .getInputStream().readAllBytes();
        shardingDataSource = YamlShardingSphereDataSourceFactory.createDataSource(yamlBytes);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(shardingDataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/OrderMapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        orderMapper = sqlSession.getMapper(OrderMapper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        sqlSession.close();
        if (shardingDataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private void createShardTables() throws Exception {
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS undo_log");
            for (int i = 0; i < 4; i++) {
                st.execute("DROP TABLE IF EXISTS orders_" + i);
                st.execute("CREATE TABLE orders_" + i + " (" +
                        "id BIGINT PRIMARY KEY," +
                        "user_id BIGINT NOT NULL," +
                        "product_id BIGINT NOT NULL," +
                        "count INT NOT NULL," +
                        "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }
            // Seata AT 回滚表：未配置分片的表由 single 规则穿透到 ds0，
            // 用于复现 Seata 启动检查（SELECT 1 FROM undo_log LIMIT 1）的场景
            st.execute("CREATE TABLE undo_log (" +
                    "branch_id BIGINT NOT NULL," +
                    "xid VARCHAR(128) NOT NULL," +
                    "context VARCHAR(128) NOT NULL," +
                    "rollback_info LONGBLOB NOT NULL," +
                    "log_status INT NOT NULL," +
                    "log_created DATETIME(6) NOT NULL," +
                    "log_modified DATETIME(6) NOT NULL)");
        }
    }

    @Test
    void unconfiguredUndoLogTableQueryPassesThrough() throws Exception {
        // Seata 2.x 启动时会对数据源执行同款检查 SQL（SELECT 1 FROM undo_log LIMIT 1），
        // undo_log 未配置分片，必须能由 single 规则穿透到物理库执行，
        // 否则 binder 直接抛 TableNotFoundException（本类 setUp 之前的报错场景）。
        // 空表时 SELECT 1 ... FROM 天然返回 0 行，Seata 只看是否执行成功；
        // 这里插入一行验证查询确实落在物理 undo_log 表上。
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO undo_log (branch_id, xid, context, rollback_info, log_status, log_created, log_modified) " +
                    "VALUES (1, 'xid', 'ctx', X'00', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        try (Connection conn = shardingDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM undo_log LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void insertRoutesToShardTableByIdMod4() throws Exception {
        for (long id = 1000; id <= 1003; id++) {
            orderMapper.insertOrder(Order.builder()
                    .id(id).userId(1L).productId(2L).count(3)
                    .build());
        }

        // id % 4 == 0..3 分别落在 orders_0..orders_3
        for (int i = 0; i < 4; i++) {
            assertThat(countByTable("orders_" + i)).as("orders_%d 应恰好 1 行", i).isEqualTo(1);
            assertThat(existsIn("orders_" + i, 1000L + i)).as("orders_%d 应包含 id=%d", i, 1000L + i).isTrue();
        }
    }

    @Test
    void selectOrderByIdRoutesToCorrectShard() throws Exception {
        orderMapper.insertOrder(Order.builder()
                .id(1002L).userId(7L).productId(8L).count(2)
                .build());

        Optional<Order> found = orderMapper.selectOrderById(1002L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(1002L);
        assertThat(found.get().getUserId()).isEqualTo(7L);
        assertThat(found.get().getProductId()).isEqualTo(8L);
        assertThat(found.get().getCount()).isEqualTo(2);
        assertThat(found.get().getCreateTime()).isNotNull();

        // 数据确实落在 orders_2，而非其它分片
        assertThat(existsIn("orders_2", 1002L)).isTrue();
        assertThat(countByTable("orders_0")).isZero();
        assertThat(countByTable("orders_1")).isZero();
        assertThat(countByTable("orders_3")).isZero();

        assertThat(orderMapper.selectOrderById(999L)).isEmpty();
    }

    @Test
    void baseMapperSelectByIdWorksWithSharding() throws Exception {
        orderMapper.insertOrder(Order.builder()
                .id(2001L).userId(1L).productId(1L).count(1)
                .build());

        Order found = orderMapper.selectById(2001L);
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(1L);
        assertThat(existsIn("orders_1", 2001L)).isTrue();
    }

    private int countByTable(String table) throws Exception {
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean existsIn(String table, long id) throws Exception {
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " WHERE id = " + id)) {
            return rs.next();
        }
    }
}
