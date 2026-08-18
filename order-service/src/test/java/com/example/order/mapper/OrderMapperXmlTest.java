package com.example.order.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.entity.Order;
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
 * OrderMapper XML SQL 集成测试（H2 内存库，MySQL 模式）。
 * 不启动 Spring 上下文，避免依赖 Nacos / Seata / RocketMQ 等外部组件；
 * 直接构建 MyBatis-Plus SqlSessionFactory，验证 XML 语句与实体映射真实可执行。
 */
class OrderMapperXmlTest {

    private SqlSession sqlSession;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:order_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // DB_CLOSE_DELAY=-1 会让内存库跨用例存活，先 DROP 再 CREATE 保证用例间隔离
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("CREATE TABLE orders (" +
                    "id BIGINT PRIMARY KEY," +
                    "user_id BIGINT NOT NULL," +
                    "product_id BIGINT NOT NULL," +
                    "count INT NOT NULL," +
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
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
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void insertOrderThenSelectOrderByIdReturnsRow() {
        Order order = Order.builder()
                .id(1001L).userId(1L).productId(2L).count(3)
                .build();

        int updated = orderMapper.insertOrder(order);
        assertThat(updated).isEqualTo(1);

        Optional<Order> found = orderMapper.selectOrderById(1001L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(1001L);
        assertThat(found.get().getUserId()).isEqualTo(1L);
        assertThat(found.get().getProductId()).isEqualTo(2L);
        assertThat(found.get().getCount()).isEqualTo(3);
        // create_time 不参与 insert，由数据库默认值填充并正确映射
        assertThat(found.get().getCreateTime()).isNotNull();
    }

    @Test
    void selectOrderByIdReturnsEmptyWhenMissing() {
        assertThat(orderMapper.selectOrderById(999L)).isEmpty();
    }

    @Test
    void baseMapperSelectByIdWorksWithXmlInsert() {
        orderMapper.insertOrder(Order.builder()
                .id(2001L).userId(1L).productId(1L).count(1)
                .build());

        // BaseMapper 内置方法与 XML 自定义方法共用同一实体映射
        Order found = orderMapper.selectById(2001L);
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(1L);
        assertThat(found.getCount()).isEqualTo(1);
    }
}
