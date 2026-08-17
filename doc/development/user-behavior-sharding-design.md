# 设计方案（v2）：用户行为管理 —— 统一 ShardingSphere 数据源

> 状态：已确认（2026-08-17）——进入编码
> 需求要点：user-service 用户行为管理（创建/查询）；行为表 `user_id/action/description/create_time`，分 4 张表；
> ShardingSphere-JDBC 管理分表，**不引入 Seata 任何包**（不考虑分布式事务）；分表业务可通过包/类名/SQL 目录显式感知。
>
> **版本说明**：v1 采用"默认机制 + Sharding 机制"双数据源隔离；v2 调整为**统一一个 ShardingSphere 数据源**（业界主流做法）——
> 全部表走同一数据源，不分表的表由 `!SINGLE` 规则穿透，分片规则只作用于 `user_behavior`。保留 `sharding` 关键字命名作为业务侧感知。

## 1. 架构变化总览（v1 → v2）

| 维度 | v1（双数据源） | v2（统一数据源，本次） |
| --- | --- | --- |
| 数据源 | `dataSource`（普通 MySQL）+ `shardingDataSource`（ShardingSphere） | **单一数据源**：`spring.datasource` = ShardingSphereDriver + `shardingsphere.yaml` |
| SqlSessionFactory | 两个（显式接管默认 + sharding） | **单个**：MyBatis-Plus 自动装配恢复 |
| Mapper 注册 | 两个 `@MapperScan` 按包绑定不同工厂 | **一个 `@MapperScan`** 覆盖 `com.example.user.mapper` 与 `com.example.user.sharding.mapper` |
| 未分片表 | 走默认普通数据源 | 走同一数据源，`!SINGLE` 规则穿透到 `ds0` |
| 事务 | 跨源不原子（v1 文档中的"事务边界"坑） | **单一本地事务管理器，可同时覆盖分表 + 不分表表**（同物理库） |
| 配置复杂度 | 双数据源/双工厂/双扫描，需显式接管 | 回归自动装配，大幅简化 |

**收益**：跨表本地事务恢复（例如"写 users（不分表）+ 写 user_behavior（分表）"可以在一个 `@Transactional` 内原子提交/回滚）；
配置大幅简化；架构贴近真实业务主流。

**代价（本次调整的核心语义变化）**：所有表的 SQL 都经过 ShardingSphere 解析后路由——不分表的表只是被
`!SINGLE` 穿透，多一次进程内解析（微秒级），不再存在"默认机制完全不碰 ShardingSphere"的物理隔离。这正是本次调整的目的。

## 2. 配置设计

### 2.1 `application.yml`

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    # 关键：Flyway 直连物理库执行 DDL（建 user_behavior_0~3 等），绕过 ShardingSphere 逻辑表路由
    url: jdbc:mysql://127.0.0.1:3306/seata_user?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    user: root
    password: ""
  datasource:
    driver-class-name: org.apache.shardingsphere.driver.ShardingSphereDriver
    url: jdbc:shardingsphere:classpath:shardingsphere.yaml
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 10000
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848

mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml,classpath*:sharding-mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
  global-config:
    banner: false
```

- `spring.datasource` 指向 ShardingSphere 驱动，物理库与分片规则都在 `shardingsphere.yaml` 中声明；
- Flyway 直连物理库（`spring.flyway.url`），管理 `user_behavior_0~3` 与历史表，不经过 ShardingSphere；
- `mapper-locations` 同时加载默认 `mapper/**/*.xml` 与 `sharding-mapper/*.xml`（同一工厂，无冲突）。

### 2.2 `shardingsphere.yaml`（沿用现有文件，更新注释定位）

```yaml
dataSources:
  ds0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/seata_user?...
    username: root
    password: ""
rules:
  - !SINGLE          # 全库不分表的表（users / user_points / sync_log / flyway_schema_history ...）穿透到 ds0
    tables:
      - "*.*"
    defaultDataSource: ds0
  - !SHARDING        # 分片规则只作用于 user_behavior
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
  sql-show: true
```

> 不配置 `transaction` 段（默认 LOCAL）→ 不触发分布式事务 SPI，无需 Seata/JTA 兼容依赖（与 v1 一致）。

### 2.3 装配简化（删除双工厂配置）

- **删除** `com.example.user.sharding.config.ShardingSphereConfig`（双数据源/双工厂/双 `@MapperScan` 不再需要）；
- **恢复** MyBatis-Plus 自动装配（单一数据源，`@ConditionalOnSingleCandidate` / `@ConditionalOnMissingBean` 均自然满足）；
- **`UserApplication`** 恢复 `@MapperScan` 并覆盖两个包：
  ```java
  @MapperScan({"com.example.user.mapper", "com.example.user.sharding.mapper"})
  ```
- 现有 mapper（`UserMapper` 等）与 XML 一行不改；`ShardingUserBehaviorMapper` 不加 `@Mapper`，由 `@MapperScan` 注册到同一工厂。

## 3. 事务边界（v2 的收益）

- 单一数据源 → Boot 自动装配**单一 `DataSourceTransactionManager`**，`@Transactional` 恢复"默认即生效"的直觉；
- **分表 + 不分表可同事务**：`users`（不分表）与 `user_behavior`（分表）同属 `ds0` 同一物理库，
  ShardingSphere LOCAL 事务在事务上下文中保持同一条物理连接，可一起提交/回滚；
- 仍**无 Seata、无分布式事务**：跨物理库才需要 XA/分布式事务，本项目不涉及。

## 4. 感知方式（保留 sharding 关键字）

统一数据源后，基础设施层面不再有"两套机制"，但业务侧感知保留：

1. **包/类名**：分表业务仍在 `com.example.user.sharding.*`，类名 `ShardingUserBehavior*`；
2. **SQL 目录**：分表 SQL 仍在 `sharding-mapper/`，与默认 `mapper/` 目录区分；
3. **运行日志**：`sql-show: true` 下，分表 SQL 显示 `Actual SQL: INSERT INTO user_behavior_1 ...`（路由改写），不分表 SQL 原样穿透；
4. **路由测试**：`ShardingUserBehaviorRoutingTest` 保留，并已有"未分片表经 `!SINGLE` 穿透"用例，正好验证统一数据源下不分表表可正常访问。

## 5. 数据设计（不变）

逻辑表 `user_behavior` → `user_behavior_0 ~ user_behavior_3`，分片键 `user_id`（`user_id % 4`）；
字段 `id`（雪花主键，sca-common `SnowflakeIdGenerator`）、`user_id`、`action`、`description`、`create_time`（DB 默认 `CURRENT_TIMESTAMP(6)`）；
`V5__add_user_behavior.sql` 建 4 张物理表（Flyway 直连执行）。接口不变：`POST /user-behavior`（创建）、`GET /user-behavior?userId=&limit=`（查询）。

## 6. 测试方案

- `ShardingUserBehaviorRoutingTest`（H2 + 真实 driver）**不变**：验证 `user_id % 4` 路由、单分片查询、按 id 广播合并、`!SINGLE` 穿透；
- 回归：现有 user-service 全部测试保持通过（mapper/XML/SQL 未动）；
- 本机验证：重启 user-service → Flyway 直连建表 → `POST/GET /user-behavior` 正常，日志可见分表路由、不分表表（如 users）访问正常；
- 启动顺序：Flyway（`spring.flyway.url` 直连）在 refresh 期间完成，ShardingSphere 数据源懒连接（首个请求/健康检查），表已就绪。

## 7. 风险与注意事项

- **`!SINGLE` 全量发现**：`*.*` 会在建连时加载全库表元数据，表多时启动略慢；本项目库小可接受，生产可按需改为显式列清单；
- **Flyway 必须直连**：`spring.flyway.url` 不能省，否则 Flyway 会尝试经 ShardingSphere 执行 DDL（行为不可预期）；
- **解析开销**：不分表 SQL 多一次进程内解析（微秒级），整体影响可忽略，符合"统一数据源"的主流取舍；
- **感知变化**：不再有"默认机制不碰 ShardingSphere"的物理隔离——这是本次调整的明确意图。

## 8. 变更文件清单

**修改**

| 文件 | 说明 |
| --- | --- |
| `user-service/src/main/resources/application.yml` | `spring.datasource` 改为 ShardingSphere 驱动；补 `spring.flyway.url/user/password` 直连；`mapper-locations` 增加 `sharding-mapper/*.xml` |
| `user-service/src/main/java/com/example/user/UserApplication.java` | `@MapperScan` 覆盖两个包 |
| `user-service/src/main/resources/shardingsphere.yaml` | 注释定位更新（全库统一数据源，`!SINGLE` 管所有不分表） |
| 本文档 | v1 → v2 方案记录 |

**删除**

| 文件 | 说明 |
| --- | --- |
| `com.example.user.sharding.config.ShardingSphereConfig` | 双工厂装配不再需要，MP 自动装配回归 |

**保留不动**：`sharding` 模块（mapper/service/controller/entity/dto）、`sharding-mapper/*.xml`、`V5__add_user_behavior.sql`、
现有 `mapper/**` 与所有 mapper 接口、`ShardingUserBehaviorRoutingTest` 及测试 YAML。
