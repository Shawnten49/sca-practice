package com.example.user.sharding;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.entity.UserBehavior;
import com.example.user.sharding.mapper.ShardingUserBehaviorMapper;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户行为分片路由集成测试（H2 内存库 + 4 张分片表，不启动 Spring 上下文）。
 *
 * <p>验证与生产一致的分片规则（user_behavior 逻辑表 → user_behavior_0~3，user_id % 4）：
 * 插入按 user_id 路由到单张物理表；按用户查询命中单分片；未分片表由 !SINGLE 规则穿透。
 */
class ShardingUserBehaviorRoutingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:user_behavior_shard_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private JdbcDataSource rawDataSource;
    private DataSource shardingDataSource;
    private ShardingUserBehaviorMapper shardingUserBehaviorMapper;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() throws Exception {
        rawDataSource = new JdbcDataSource();
        rawDataSource.setURL(H2_URL);
        rawDataSource.setUser("sa");
        createShardTables();

        byte[] yamlBytes = new ClassPathResource("shardingsphere-user-behavior-test.yaml")
                .getInputStream().readAllBytes();
        shardingDataSource = YamlShardingSphereDataSourceFactory.createDataSource(yamlBytes);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(shardingDataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:sharding-mapper/*.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        shardingUserBehaviorMapper = sqlSession.getMapper(ShardingUserBehaviorMapper.class);
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
            for (int i = 0; i < 4; i++) {
                st.execute("DROP TABLE IF EXISTS user_behavior_" + i);
                st.execute("CREATE TABLE user_behavior_" + i + " (" +
                        "id BIGINT PRIMARY KEY," +
                        "user_id BIGINT NOT NULL," +
                        "action VARCHAR(64) NOT NULL," +
                        "description VARCHAR(255)," +
                        "create_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
            }
            // 未分片表：验证 !SINGLE 规则穿透
            st.execute("DROP TABLE IF EXISTS plain_table");
            st.execute("CREATE TABLE plain_table (id BIGINT PRIMARY KEY)");
            st.execute("INSERT INTO plain_table (id) VALUES (1)");
        }
    }

    @Test
    void insertsRouteByUserIdMod4() throws Exception {
        // user_id: 1→表1, 2→表2, 3→表3, 4→表0, 5→表1
        insert(1L, "login", "用户1登录");
        insert(2L, "order", "用户2下单");
        insert(3L, "click", "用户3点击");
        insert(4L, "login", "用户4登录");
        insert(5L, "login", "用户5登录");

        assertThat(countInShard(0)).isEqualTo(1);   // user 4
        assertThat(countInShard(1)).isEqualTo(2);   // user 1, 5
        assertThat(countInShard(2)).isEqualTo(1);   // user 2
        assertThat(countInShard(3)).isEqualTo(1);   // user 3
    }

    @Test
    void selectByUserIdHitsSingleShard() {
        insert(1L, "login", "用户1登录");
        insert(5L, "order", "用户5下单");
        insert(9L, "click", "用户9点击");   // 9 % 4 = 1，与 user 1/5 同分片

        List<UserBehavior> behaviors = shardingUserBehaviorMapper.selectByUserId(5L, 50);

        assertThat(behaviors).hasSize(1);
        assertThat(behaviors.get(0).getUserId()).isEqualTo(5L);
        assertThat(behaviors.get(0).getAction()).isEqualTo("order");
        assertThat(behaviors.get(0).getCreateTime()).isNotNull();
    }

    @Test
    void selectByIdBroadcastsAndMerges() {
        Long id = insert(7L, "login", "用户7登录");   // 7 % 4 = 3

        UserBehavior behavior = shardingUserBehaviorMapper.selectById(id);

        assertThat(behavior).isNotNull();
        assertThat(behavior.getId()).isEqualTo(id);
        assertThat(behavior.getUserId()).isEqualTo(7L);
        assertThat(behavior.getCreateTime()).isNotNull();
    }

    @Test
    void unshardedTablePassesThroughViaSingleRule() throws Exception {
        try (Connection conn = shardingDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM plain_table")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1L);
        }
    }

    private Long insert(Long userId, String action, String description) {
        UserBehavior record = new UserBehavior();
        record.setId(System.nanoTime());
        record.setUserId(userId);
        record.setAction(action);
        record.setDescription(description);
        shardingUserBehaviorMapper.insertUserBehavior(record);
        return record.getId();
    }

    private long countInShard(int shard) throws Exception {
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user_behavior_" + shard)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
