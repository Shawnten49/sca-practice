package com.example.stock.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.stock.entity.Product;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductMapper XML SQL 集成测试（H2 内存库，MySQL 模式）。
 * 不启动 Spring 上下文，直接构建 MyBatis-Plus SqlSessionFactory，
 * 验证保存、按 id 回查与按名称模糊查询（ES 降级路径）真实可执行。
 */
class ProductMapperXmlTest {

    private SqlSession sqlSession;
    private ProductMapper productMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:product_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // DB_CLOSE_DELAY=-1 会让内存库跨用例存活，先 DROP 再 CREATE 保证用例间隔离
            st.execute("DROP TABLE IF EXISTS product");
            st.execute("CREATE TABLE product (" +
                    "id BIGINT PRIMARY KEY," +
                    "name VARCHAR(128) NOT NULL," +
                    "brand VARCHAR(64) NOT NULL," +
                    "price DECIMAL(10,2) NOT NULL," +
                    "description VARCHAR(512) NOT NULL DEFAULT ''," +
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/ProductMapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();

        SqlSessionFactory sessionFactory = factoryBean.getObject();
        sqlSession = sessionFactory.openSession(true);
        productMapper = sqlSession.getMapper(ProductMapper.class);
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void insertProductThenSelectByIdRoundTrip() {
        productMapper.insertProduct(Product.builder()
                .id(100L).name("华为 Mate 70 Pro").brand("华为")
                .price(new BigDecimal("6999.00")).description("麒麟芯片旗舰手机")
                .build());

        Optional<Product> found = productMapper.selectProductById(100L);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("华为 Mate 70 Pro");
        assertThat(found.get().getBrand()).isEqualTo("华为");
        assertThat(found.get().getPrice()).isEqualByComparingTo("6999.00");
        assertThat(found.get().getDescription()).isEqualTo("麒麟芯片旗舰手机");
        assertThat(found.get().getCreateTime()).isNotNull();
    }

    @Test
    void selectProductByIdReturnsEmptyWhenMissing() {
        assertThat(productMapper.selectProductById(999L)).isEmpty();
    }

    @Test
    void selectByNameLikeMatchesKeyword() {
        productMapper.insertProduct(Product.builder()
                .id(1L).name("华为 Mate 70 Pro").brand("华为").price(new BigDecimal("6999.00")).description("").build());
        productMapper.insertProduct(Product.builder()
                .id(2L).name("华为 Mate 60").brand("华为").price(new BigDecimal("4999.00")).description("").build());
        productMapper.insertProduct(Product.builder()
                .id(3L).name("小米 15").brand("小米").price(new BigDecimal("3999.00")).description("").build());

        List<Product> matched = productMapper.selectByNameLike("华为");

        assertThat(matched).hasSize(2);
        assertThat(matched).extracting(Product::getId).containsExactly(1L, 2L);
    }
}
