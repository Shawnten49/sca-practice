# 设计方案：用户行为管理（ShardingSphere-JDBC 分表模块）

> 状态：待评审（评审通过后进入编码）
> 需求要点：user-service 新增用户行为管理（创建/查询）；行为表 `user_id/action/description/create_time`，数据量大分 4 张表；
> ShardingSphere-JDBC 管理分表，**不引入 Seata 任何包**（不考虑分布式事务）；
> 建立两套数据机制——默认机制（现有 mapper/sql xml/datasource，不引入 ShardingSphere，**行为不改动**）与 Sharding 机制
> （关键字 `sharding` 区分：包路径、类名、sql xml 均体现），行为表走 Sharding 机制，让开发者**显式感知**是否走了 ShardingSphere-JDBC。

## 1. 总体结构

新增模块整体收口在 `com.example.user.sharding` 包（与默认 `com.example.user.mapper` 等完全隔离），类名统一加 `Sharding` 前缀：

```
com.example.user.sharding
├── controller.ShardingUserBehaviorController   # GET/POST /user-behavior（创建、查询）
├── service.ShardingUserBehaviorService          # 业务编排：id 生成、参数校验、调 sharding mapper
├── mapper.ShardingUserBehaviorMapper            # 走 ShardingSphere-JDBC 的 mapper（无 @Mapper，由 @MapperScan 绑定）
├── domain.UserBehavior                          # 行为实体（POJO，Lombok）
└── config.ShardingSphereConfig                  # 总装配：默认 + Sharding 双数据源/双 SqlSessionFactory + @MapperScan

src/main/resources
├── shardingsphere.yaml                          # Sharding 数据源/分片规则（未来其他分表业务复用此文件）
├── sharding-mapper/ShardingUserBehaviorMapper.xml  # Sharding 专用 SQL（与默认 mapper/ 目录隔离）
└── db/migration/V5__add_user_behavior.sql       # 建 4 张物理表（Flyway 直连默认库执行）
```

分层调用：

```
ShardingUserBehaviorController
   └─ ShardingUserBehaviorService（参数校验 → SnowflakeIdGenerator 生成 id → 调 mapper）
        └─ ShardingUserBehaviorMapper（ShardingSphere-JDBC 路由到 user_behavior_0~3）
```

## 2. 两套数据机制与隔离原则

| 维度 | 默认机制（行为不变） | Sharding 机制（新增） |
| --- | --- | --- |
| 数据源 | `dataSource`（@Primary，普通 MySQL，配置仍来自 `spring.datasource.*`） | `shardingDataSource`（ShardingSphere-JDBC，来自 `shardingsphere.yaml`） |
| SqlSessionFactory | `sqlSessionFactory`（@Primary，配置仍来自 `application.yml` 的 `mybatis-plus.*`） | `shardingSqlSessionFactory`（只加载 `sharding-mapper/*.xml`） |
| Mapper 注册 | `@MapperScan("com.example.user.mapper")` → 默认工厂 | `@MapperScan("com.example.user.sharding.mapper")` → sharding 工厂 |
| Mapper 类名 | 如 `UserMapper` | `ShardingUserBehaviorMapper` |
| SQL XML | `resources/mapper/**/*.xml` | `resources/sharding-mapper/*.xml` |
| 事务 | 沿用现状 | 无 Seata、无分布式事务；单条 INSERT/SELECT，autocommit |

### 2.1 为什么默认数据源/默认工厂要"显式接管"（这是采用该方案的必要代价）

参考 order-service 的 `OrderDataSourceConfig`（双工厂 + `@MapperScan(sqlSessionFactoryRef=...)` 标准模式），
但有两个自动装配约束决定了**默认机制的定义必须从自动装配移交到显式配置**（行为完全一致，只是"由谁定义"变化）：

1. MyBatis-Plus 自动装配的 `sqlSessionFactory` 是 **`@ConditionalOnMissingBean(SqlSessionFactory.class)`（按类型）**：
   出现第二个 SqlSessionFactory Bean（sharding 工厂）就会整体退避，必须显式补回默认工厂；
2. Boot 自动装配的 `dataSource` 不是 `@Primary`：出现第二个 DataSource Bean（sharding 数据源）后，
   MP 的 `@ConditionalOnSingleCandidate(DataSource.class)` 也会失效，必须显式声明 `@Primary` 默认数据源。

因此 `ShardingSphereConfig` 同时定义：`dataSource`（@Primary，从 `DataSourceProperties` 读 `spring.datasource.*`）、
`sqlSessionFactory`（@Primary，从 `MybatisPlusProperties` 读 `mybatis-plus.*`，与 MP 自动装配行为一致）、
`shardingDataSource`、`shardingSqlSessionFactory`，以及两个 `@MapperScan`。

**"默认机制不要改动"的界定**：mapper 接口、XML、SQL、默认数据源连接参数、事务行为全部不变；
变化的是默认数据源/工厂的 Bean 定义位置（自动装配 → `ShardingSphereConfig`），这是双工厂模式的必然要求。

### 2.2 扫描全部 ShardingMapper：`@MapperScan` 按包绑定（不写死单个 mapper）

```java
@Configuration
@MapperScan(basePackages = "com.example.user.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.example.user.sharding.mapper", sqlSessionFactoryRef = "shardingSqlSessionFactory")
public class ShardingSphereConfig {

    // ========== 默认机制（行为与 MP 自动装配一致） ==========

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        HikariDataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(10_000);
        return dataSource;
    }

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
        MybatisConfiguration configuration = new MybatisConfiguration();
        mybatisPlusProperties.getConfiguration().applyTo(configuration);   // MP 3.5.17 的 CoreConfiguration
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(mybatisPlusProperties.getGlobalConfig());
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    // ========== Sharding 机制 ==========

    @Bean
    public DataSource shardingDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(ShardingSphereDriver.class.getName());
        dataSource.setJdbcUrl("jdbc:shardingsphere:classpath:shardingsphere.yaml");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean
    public SqlSessionFactory shardingSqlSessionFactory(@Qualifier("shardingDataSource") DataSource shardingDataSource,
                                                       ApplicationContext applicationContext) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(shardingDataSource);
        factoryBean.setVfs(SpringBootVFS.class);
        factoryBean.setApplicationContext(applicationContext);
        // 只加载 sharding-mapper/*.xml，默认工厂的 classpath*:mapper/**/*.xml 不会扫到它
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:sharding-mapper/*.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }
}
```

> 约定：`com.example.user.sharding.mapper` 包下只放 ShardingMapper 接口；未来新增分表业务，只需
> "接口放该包 + XML 放 `sharding-mapper/` + 在 `shardingsphere.yaml` 追加规则"，`@MapperScan` 自动注册，零配置。
> `UserApplication` 上原有的 `@MapperScan("com.example.user.mapper")` 移除，统一收口到本配置类。

### 2.3 依赖（无 Seata）

user-service pom 新增（版本由父 POM `shardingsphere.version=5.5.3` 统一管理，均已存在于 dependencyManagement）：

- `shardingsphere-jdbc`
- `shardingsphere-sharding-core`
- `shardingsphere-infra-data-source-pool-hikari`
- `shardingsphere-standalone-mode-core` / `shardingsphere-standalone-mode-repository-memory`
- `shardingsphere-authority-simple`
- `shardingsphere-parser-sql-engine-mysql`
- `shardingsphere-infra-url-classpath`

**不引入**：`org.apache.seata:*`（user-service 当前无任何 seata 依赖）、`shardingsphere-transaction-base-seata-at`、`javax.transaction-api`。
理由：本模块 YAML **不配置 `transaction` 段**（默认 LOCAL），不会触发 ShardingSphere 分布式事务 SPI 预加载，因此也不需要
order-service 那套 Seata/JTA 兼容依赖（那些是 Seata 集成场景才需要）。

## 3. 数据设计

### 3.1 表结构（逻辑表 `user_behavior` → 物理表 `user_behavior_0 ~ user_behavior_3`）

```sql
CREATE TABLE user_behavior_0 (
    id          BIGINT       NOT NULL COMMENT '雪花ID（全局唯一，服务端生成）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    action      VARCHAR(64)  NOT NULL COMMENT '行为类型，如 login / order / click',
    description VARCHAR(255) DEFAULT NULL COMMENT '行为描述',
    create_time DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_user_create (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户行为分片表 0';
-- user_behavior_1 ~ user_behavior_3 同构
```

设计说明：

- **分片键 = `user_id`，规则 `user_id % 4`**：创建、按用户查询都能精确路由到单张物理表，避免广播；
- **`id` 为雪花主键**（复用 sca-common 的 `SnowflakeIdGenerator`，本机单实例 machineId 固定值）；
- **create_time 由数据库默认值填充**（`CURRENT_TIMESTAMP(6)`），应用层不传。

### 3.2 Flyway 迁移 `V5__add_user_behavior.sql`

在默认数据源（`seata_user`，Flyway 直连，不经 ShardingSphere）创建 4 张物理表，幂等可重复执行：

```sql
CREATE TABLE IF NOT EXISTS user_behavior_0 (...);
CREATE TABLE IF NOT EXISTS user_behavior_1 (...);
CREATE TABLE IF NOT EXISTS user_behavior_2 (...);
CREATE TABLE IF NOT EXISTS user_behavior_3 (...);
```

> 启动顺序无需额外处理：sharding 数据源是懒连接（首个请求才建连），此时 Flyway 早已迁移完成；
> 若后续改为启动即建连且报"表不存在"，在 `shardingDataSource` Bean 上加 `@DependsOn("flywayInitializer")` 即可。

## 4. ShardingSphere 配置

### 4.1 `shardingsphere.yaml`

```yaml
dataSources:
  ds0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/seata_user?createDatabaseIfNotExist=true&useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ""
    maximumPoolSize: 5
    minimumIdle: 1
rules:
  - !SINGLE
    tables:
      - "*.*"
    defaultDataSource: ds0
  - !SHARDING
    tables:
      user_behavior:
        actualDataNodes: ds0.user_behavior_$->{0..3}
        tableStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: user-behavior-user-id-mod-4
    shardingAlgorithms:
      user-behavior-user-id-mod-4:
        type: INLINE
        props:
          algorithm-expression: user_behavior_$->{user_id % 4}
props:
  sql-show: true   # 打印路由 SQL，让开发者直观看到物理表，验证后可关
```

- 无 `transaction` 段（默认 LOCAL）→ 不需要 Seata/JTA 兼容依赖；
- `!SINGLE` 防御性声明：即使未来误用该数据源访问其他表，也能穿透到默认库而不是抛 `TableNotFoundException`；
- 文件名统一为 `shardingsphere.yaml`：未来其他分表业务直接在此文件追加 `!SHARDING` 规则，配置类无需改名。

## 5. 接口定义

### 创建（推荐风格）

```
POST /user-behavior
body: { "userId": 1, "action": "login", "description": "登录成功" }
```

- 校验：userId 正整数、action 非空（≤64）、description 可空（≤255）；
- 成功：`HTTP 200` + `Result<UserBehavior>`（`code=0`，data 含生成的 id 与 create_time）；
- 失败：沿用全局异常处理器输出 ProblemDetail。

### 查询

```
GET /user-behavior?userId=1&limit=50
```

- 校验：userId 必填正整数；limit 默认 50、上限 200；
- 成功：`HTTP 200` + `Result<List<UserBehavior>>`，按 `create_time DESC, id DESC` 排序（单分片查询）；
- 失败：userId 缺失/非法 → 400 ProblemDetail。

## 6. Mapper 与 SQL（sharding 机制）

### 6.1 `ShardingUserBehaviorMapper`（`com.example.user.sharding.mapper`，不加 `@Mapper`）

```java
public interface ShardingUserBehaviorMapper {

    int insertUserBehavior(UserBehavior record);

    List<UserBehavior> selectByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);
}
```

> 不加 `@Mapper`：该接口由 `@MapperScan(basePackages="com.example.user.sharding.mapper", sqlSessionFactoryRef="shardingSqlSessionFactory")`
> 绑定到 sharding 工厂；若加了 `@Mapper` 反而可能被 MP 自动扫描路径误注册到默认工厂（本项目显式 @MapperScan 场景下无碍，但保持零注解更干净）。

### 6.2 `sharding-mapper/ShardingUserBehaviorMapper.xml`

```xml
<mapper namespace="com.example.user.sharding.mapper.ShardingUserBehaviorMapper">
    <insert id="insertUserBehavior" parameterType="com.example.user.sharding.domain.UserBehavior">
        INSERT INTO user_behavior (id, user_id, action, description, create_time)
        VALUES (#{id}, #{userId}, #{action}, #{description}, CURRENT_TIMESTAMP(6))
    </insert>

    <select id="selectByUserId" resultType="com.example.user.sharding.domain.UserBehavior">
        SELECT id, user_id, action, description, create_time
        FROM user_behavior
        WHERE user_id = #{userId}
        ORDER BY create_time DESC, id DESC
        LIMIT #{limit}
    </select>
</mapper>
```

## 7. 用户如何"显式感知"走了 ShardingSphere-JDBC

1. **包路径**：`com.example.user.sharding.*`；默认机制在 `com.example.user.mapper` 等，互不相交；
2. **类名**：`ShardingUserBehaviorController / Service / Mapper`，前缀 `Sharding`；
3. **SQL 目录**：`resources/sharding-mapper/`，默认机制在 `resources/mapper/`；
4. **运行日志**：`sql-show: true` 打印 `Actual SQL: INSERT INTO user_behavior_1 ...`（物理表名可见），默认 mapper 的日志无路由改写；
5. **Controller 路径**：`/user-behavior` 为本模块专属入口，一眼可辨。

## 8. 测试方案

- 新增 `ShardingUserBehaviorRoutingTest`（H2 + 真实 `ShardingSphereDriver`，测试 YAML 用 `jdbc:h2:mem`，不启动 Spring 上下文）：
  - 建 4 张 `user_behavior_0~3`，按不同 `user_id` 插入，断言分别路由到对应物理表（`user_id % 4`）；
  - 按 `user_id` 查询，断言只命中单张物理表；
  - 断言未配置分片的表（如 `flyway_schema_history`）经 `!SINGLE` 规则可穿透（防御性）。
- 回归：现有 user-service 全部测试保持通过（默认 mapper/xml/SQL 零改动）。
- 本机验证：重启 user-service → `POST /user-behavior` 创建多条不同 userId → `GET /user-behavior?userId=` 查询；
  日志可见 `Actual SQL` 路由到 `user_behavior_?`。

## 9. 风险与后续扩展

- **多实例雪花冲突**：`SnowflakeIdGenerator` 的 machineId 需按实例区分；本机单实例固定值即可，部署多实例时改为配置注入；
- **事务边界（重点）**：双数据源意味着"一个事务管理器只能管一个数据源"，使用 `@Transactional` 前必须分清三种情况：

  | 场景 | 行为 | 说明 |
  | --- | --- | --- |
  | 只操作默认数据源（不分表） | `@Transactional` 照常生效 | 绑定默认 `transactionManager`（@Primary 默认数据源），MySQL 本地事务不变 |
  | 只操作 Sharding 数据源（分表） | 未命名 `@Transactional` **静默失效** | 它绑定的是默认数据源的事务管理器，管不到 sharding 连接；sharding SQL 实际按 autocommit 执行，不报错但无原子性。需要原子性时必须显式定义 `shardingTransactionManager`（DataSourceTransactionManager 绑定 shardingDataSource）并用 `@Transactional("shardingTransactionManager")` |
  | 不分表 + 分表混合在一个方法 | 单个 `@Transactional` **不原子** | 跨两个数据源/两个连接池；即使默认库与 sharding 数据源指向同一物理库，也是两条独立连接、各自独立的本地事务，无法一起回滚 |

  **取舍**：
  - 各自原子、整体不原子：默认部分 `@Transactional`，分表部分 `@Transactional("shardingTransactionManager")`，业务上接受短暂不一致并补偿（符合"默认机制不碰 ShardingSphere"的约束）；
  - 强原子：把不分表的表也纳入 sharding 数据源（`!SINGLE` 规则穿透），统一走 sharding 本地事务——但这会让默认机制也绕进 ShardingSphere，违背本设计前提；
  - 分布式事务（XA/Seata）才能跨数据源强一致，本项目明确排除（不引入 Seata）。

  **本模块现状**：创建/查询均为单条 SQL、单分片，autocommit 即可，不涉及事务；若未来出现"先写默认表再写分表且要求同生共死"的业务，必须按上述取舍显式设计事务边界；
- **新增分表业务**：只加 ShardingMapper 接口 + `sharding-mapper/*.xml` + 在 `shardingsphere.yaml` 追加分片规则，其余零改动；
- **H2 兼容**：测试用 `NOW(6)` 若 H2 版本不支持，测试中改 `CURRENT_TIMESTAMP(6)` 或由测试自行传入 create_time，生产 SQL 不变。

## 10. 变更文件清单

**新增**

| 文件 | 说明 |
| --- | --- |
| `user-service/pom.xml`（修改） | 追加 shardingsphere 依赖（无 seata） |
| `user-service/src/main/resources/shardingsphere.yaml` | Sharding 数据源与分片规则（后续分表业务复用） |
| `user-service/src/main/resources/sharding-mapper/ShardingUserBehaviorMapper.xml` | Sharding 专用 SQL |
| `user-service/src/main/resources/db/migration/V5__add_user_behavior.sql` | 建 4 张物理表 |
| `com.example.user.sharding.domain.UserBehavior` | 行为实体 |
| `com.example.user.sharding.mapper.ShardingUserBehaviorMapper` | Sharding mapper |
| `com.example.user.sharding.service.ShardingUserBehaviorService` | 业务编排 |
| `com.example.user.sharding.controller.ShardingUserBehaviorController` | 创建/查询接口 |
| `com.example.user.sharding.config.ShardingSphereConfig` | 双数据源/双工厂装配 + 两个 `@MapperScan` |
| `ShardingUserBehaviorRoutingTest` + 测试 YAML | H2 路由回归 |

**修改**

| 文件 | 说明 |
| --- | --- |
| `UserApplication.java` | 移除原 `@MapperScan("com.example.user.mapper")`（收口到 `ShardingSphereConfig`） |

**不改动**：现有 mapper 接口与 XML、现有 Service/Controller、默认数据源连接参数（仍来自 `spring.datasource.*`）、默认事务行为。
