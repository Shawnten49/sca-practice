package com.example.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.entity.User;
import com.example.user.mapper.UserMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShardingSphere ENCRYPT + MASK 集成测试（H2 内存库，不启动 Spring 上下文）。
 *
 * <p>验证 users.id_card 的完整数据链：写入时逻辑列明文 → 物理列 id_card_cipher 密文；
 * 查询时密文 → 解密 → KEEP_FIRST_N_LAST_M 脱敏碎片返回；空串约定（非 null、默认 ''）。
 */
class UserEncryptMaskTest {

    private static final String AES_KEY = "test-key-5.5.3";
    private static final String ID_CARD = "110101199003071234";
    private static final String MASKED_ID_CARD = "110***********1234";

    private JdbcDataSource rawDataSource;
    private DataSource shardingDataSource;
    private UserMapper userMapper;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() throws Exception {
        rawDataSource = new JdbcDataSource();
        // CASE_INSENSITIVE_IDENTIFIERS=TRUE：ShardingSphere 改写后以带引号小写列名访问，
        // H2 默认把未加引号列名存为大写、带引号查找大小写敏感（MySQL 生产无此问题），需显式开启
        rawDataSource.setURL("jdbc:h2:mem:user_encrypt_mask_test;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        rawDataSource.setUser("sa");
        createUsersTable();

        byte[] yamlBytes = new ClassPathResource("user-encrypt-mask-test.yaml")
                .getInputStream().readAllBytes();
        shardingDataSource = YamlShardingSphereDataSourceFactory.createDataSource(yamlBytes);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(shardingDataSource);
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
    }

    @AfterEach
    void tearDown() throws Exception {
        sqlSession.close();
        if (shardingDataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private void createUsersTable() throws Exception {
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("CREATE TABLE users (" +
                    "id BIGINT PRIMARY KEY," +
                    "nickname VARCHAR(64) NOT NULL," +
                    "points INT NOT NULL DEFAULT 0," +
                    "credits INT NOT NULL DEFAULT 0," +
                    "id_card_cipher VARCHAR(128) NOT NULL DEFAULT ''," +
                    "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void insertStoresCipherInsteadOfPlaintext() throws Exception {
        insertUser(100L, ID_CARD);

        String cipherText = readCipher(100L);
        assertThat(cipherText).isNotBlank();
        assertThat(cipherText).isNotEqualTo(ID_CARD);
        // 密文可独立解密回明文（与 ShardingSphere 一致的 AES + SHA-1 密钥派生）
        assertThat(decryptAes(cipherText)).isEqualTo(ID_CARD);
    }

    @Test
    void selectReturnsMaskedIdCard() {
        insertUser(101L, ID_CARD);

        Optional<User> found = userMapper.selectUserById(101L);
        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("zhangsan");
        assertThat(found.get().getIdCard()).isEqualTo(MASKED_ID_CARD);
        assertThat(found.get().getCreateTime()).isNotNull();
    }

    @Test
    void legacyEmptyCipherReturnsEmptyString() throws Exception {
        // 老数据：id_card_cipher = ''（V6 迁移 DEFAULT '' 回填），查询返回空串而非 null
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO users (id, nickname, points, credits, id_card_cipher) VALUES (200, 'old', 0, 0, '')");
        }

        Optional<User> found = userMapper.selectUserById(200L);
        assertThat(found).isPresent();
        assertThat(found.get().getIdCard()).isEqualTo("");
    }

    @Test
    void blankIdCardStoresAndReturnsEmptyString() throws Exception {
        insertUser(102L, "");

        // 空串也会被 AES/PKCS5 加密为 16 字节密文（不落空值/明文），查询端解密回空串
        assertThat(readCipher(102L)).isNotBlank();
        Optional<User> found = userMapper.selectUserById(102L);
        assertThat(found).isPresent();
        assertThat(found.get().getIdCard()).isEqualTo("");
    }

    private void insertUser(Long id, String idCard) {
        userMapper.insertUser(User.builder()
                .id(id).nickname("zhangsan").points(0).credits(0).idCard(idCard)
                .build());
    }

    private String readCipher(Long id) throws Exception {
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_card_cipher FROM users WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String decryptAes(String cipherText) throws Exception {
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1")
                .digest(AES_KEY.getBytes(StandardCharsets.UTF_8)), 16);
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }
}
