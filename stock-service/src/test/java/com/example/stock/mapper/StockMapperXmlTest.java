package com.example.stock.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.stock.domain.Stock;
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
 * StockMapper XML SQL 集成测试（H2 内存库，MySQL 模式）。
 * 不启动 Spring 上下文，直接构建 MyBatis-Plus SqlSessionFactory，
 * 验证条件扣减（防超卖）与按商品号查询真实可执行。
 */
class StockMapperXmlTest {

    private SqlSession sqlSession;
    private StockMapper stockMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stock_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // DB_CLOSE_DELAY=-1 会让内存库跨用例存活，先 DROP 再 CREATE 保证用例间隔离
            st.execute("DROP TABLE IF EXISTS stock");
            st.execute("CREATE TABLE stock (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "product_id BIGINT UNIQUE NOT NULL," +
                    "quantity INT NOT NULL)");
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/StockMapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        stockMapper = sqlSession.getMapper(StockMapper.class);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void deductStockDecreasesQuantity() {
        stockMapper.insert(Stock.builder().productId(1L).quantity(100).build());

        int updated = stockMapper.deductStock(1L, 30);
        assertThat(updated).isEqualTo(1);

        Stock stock = stockMapper.selectStockByProductId(1L).orElseThrow();
        assertThat(stock.getQuantity()).isEqualTo(70);
    }

    @Test
    void deductStockInsufficientReturnsZeroAndKeepsQuantity() {
        stockMapper.insert(Stock.builder().productId(1L).quantity(10).build());

        // 扣减量大于现有库存：条件不满足，返回 0，库存不变（防超卖）
        assertThat(stockMapper.deductStock(1L, 11)).isZero();
        assertThat(stockMapper.selectStockByProductId(1L).orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void selectStockByProductIdReturnsEmptyWhenMissing() {
        assertThat(stockMapper.selectStockByProductId(99L)).isEmpty();
    }
}
