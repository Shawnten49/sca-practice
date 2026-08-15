package com.example.user.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.user.domain.User;
import com.example.user.domain.UserPoints;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserMapper / UserPointsMapper XML SQL 集成测试（H2 内存库，MySQL 模式）。
 * 不启动 Spring 上下文，直接构建 MyBatis-Plus SqlSessionFactory，
 * 验证 XML 语句、实体映射与 order_id 唯一索引幂等兜底。
 */
class UserMapperXmlTest {

    private SqlSession sqlSession;
    private UserMapper userMapper;
    private UserPointsMapper userPointsMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:user_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // DB_CLOSE_DELAY=-1 会让内存库跨用例存活，先 DROP 再 CREATE 保证用例间隔离
            st.execute("DROP TABLE IF EXISTS user_points");
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGINT PRIMARY KEY," +
                    "nickname VARCHAR(64) NOT NULL," +
                    "points INT NOT NULL DEFAULT 0," +
                    "credits INT NOT NULL DEFAULT 0 CHECK (credits >= 0)," +
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE user_points (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "user_id BIGINT NOT NULL," +
                    "order_id BIGINT NOT NULL," +
                    "points INT NOT NULL," +
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uk_user_points_order (order_id))");
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/*.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        userMapper = sqlSession.getMapper(UserMapper.class);
        userPointsMapper = sqlSession.getMapper(UserPointsMapper.class);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void selectUserByIdReturnsMappedUser() {
        // users 表没有 insert XML，用 BaseMapper 内置方法写入（@TableId INPUT 显式指定 id）
        userMapper.insert(User.builder().id(1L).nickname("demo").points(100).build());

        Optional<User> found = userMapper.selectUserById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(1L);
        assertThat(found.get().getNickname()).isEqualTo("demo");
        assertThat(found.get().getPoints()).isEqualTo(100);
        assertThat(found.get().getCredits()).isEqualTo(0);   // 未设置时取列默认值
        assertThat(found.get().getCreateTime()).isNotNull();
    }

    @Test
    void selectUserByIdReturnsEmptyWhenMissing() {
        assertThat(userMapper.selectUserById(999L)).isEmpty();
    }

    @Test
    void insertUserPointsThenSelectByOrderIdRoundTrip() {
        int updated = userPointsMapper.insertUserPoints(UserPoints.builder()
                .userId(1L).orderId(100L).points(200)
                .build());
        assertThat(updated).isEqualTo(1);

        Optional<UserPoints> found = userPointsMapper.selectByOrderId(100L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();       // 数据库自增回填
        assertThat(found.get().getUserId()).isEqualTo(1L);
        assertThat(found.get().getOrderId()).isEqualTo(100L);
        assertThat(found.get().getPoints()).isEqualTo(200);
        assertThat(found.get().getCreateTime()).isNotNull();
    }

    @Test
    void increasePointsAccumulatesUserPoints() {
        userMapper.insert(User.builder().id(1L).nickname("demo").points(100).build());

        int updated = userMapper.increasePoints(1L, 50);
        assertThat(updated).isEqualTo(1);

        Optional<User> found = userMapper.selectUserById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().getPoints()).isEqualTo(150);
    }

    @Test
    void increaseCreditsAccumulatesUserCredits() {
        userMapper.insert(User.builder().id(1L).nickname("demo").credits(50).build());

        int updated = userMapper.increaseCredits(1L, 30);
        assertThat(updated).isEqualTo(1);

        Optional<User> found = userMapper.selectUserById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().getCredits()).isEqualTo(80);
    }

    @Test
    void increaseCreditsRejectsNegativeResult() {
        userMapper.insert(User.builder().id(1L).nickname("demo").credits(50).build());

        // 扣减到负数：WHERE credits + delta >= 0 不命中，返回 0
        int updated = userMapper.increaseCredits(1L, -100);
        assertThat(updated).isZero();

        Optional<User> found = userMapper.selectUserById(1L);
        assertThat(found).isPresent();
        assertThat(found.get().getCredits()).isEqualTo(50);   // 值未变
    }

    @Test
    void duplicateOrderIdIgnoredByInsertIgnore() {
        UserPoints first = UserPoints.builder().userId(1L).orderId(100L).points(200).build();
        assertThat(userPointsMapper.insertUserPoints(first)).isEqualTo(1);

        // INSERT IGNORE：同一订单重复落库不再抛异常，返回 0，首条记录保留
        int second = userPointsMapper.insertUserPoints(
                UserPoints.builder().userId(2L).orderId(100L).points(200).build());
        assertThat(second).isZero();

        UserPoints kept = userPointsMapper.selectByOrderId(100L).orElseThrow();
        assertThat(kept.getUserId()).isEqualTo(1L);
    }
}
