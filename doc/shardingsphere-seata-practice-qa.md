# ShardingSphere 分表 + Seata 实践问答全记录

> 适用范围：sca-practice 项目（order-service 接入 ShardingSphere 5.5.3 对 `orders` 分表，并与 Seata AT 模式共存）。
> 本文汇总从"分表设计"到"Seata × ShardingSphere 兼容"再到"本地/分布式事务如何用注解显式区分"的完整问答，
> 结论均基于本机实测（Java 21 / Spring Boot 3.5.16 / Spring Cloud Alibaba 2025.0.0.0 / MySQL 8.4 / Seata 客户端 2.5.0 / ShardingSphere 5.5.3）。

## 目录

1. [分表设计与初始化](#一分表设计与初始化)
2. [启动报错：undo_log 不存在 / Flyway 顺序](#二启动报错undo_log-不存在--flyway-顺序)
3. [Seata 与 ShardingSphere 不兼容](#三seata-与-shardingsphere-不兼容)
4. [Java 21 启动报错：javax.transaction 缺失](#四java-21-启动报错javaxtransaction-缺失)
5. [@Transactional 一定走 Seata 吗](#五transactional-一定走-seata-吗)
6. [双数据源：如何用注解显式区分 Seata / 本地事务](#六双数据源如何用注解显式区分-seata--本地事务)
7. [LocalOrderMapper 注册报错与 XML 化](#七localordermapper-注册报错与-xml-化)
8. [多个 LocalMapper 的批量配置](#八多个-localmapper-的批量配置)
9. [@Mapper 与 @MapperScan 的区别](#九mapper-与-mapperscan-的区别)
10. [实测验证清单](#十实测验证清单)
11. [面试要点速记](#十一面试要点速记)

---

## 一、分表设计与初始化

### Q1. orders 表怎么分？分片键选什么？

**结论**：分片键用 `id`，`orders` 逻辑表路由到 `orders_0 ~ orders_3` 四张物理表，规则为 `id % 4`。
这是需求侧拍板的约束：用 id 分、相关代码一起改、不做数据备份（学习环境允许直接重建）。

### Q2. 技术方案怎么落地？

**结论**：ShardingSphere-JDBC 5.5.3 采用 **driver 方式**（`ShardingSphereDriver` + `jdbc:shardingsphere:classpath:shardingsphere.yaml`），
而不是课程里的 `spring.shardingsphere.*` starter（5.5.x 已不推荐）。

- `shardingsphere.yaml`：声明数据源 `ds0`（MySQL `seata_order`）、`!SHARDING` 规则（`orders_$->{0..3}`，`id % 4`）和 `!SINGLE` 规则（`*.*` → 默认数据源，管住 undo_log、Flyway 历史表等未分片表）；
- 物理表 DDL 由 Flyway 直连 MySQL 执行（`V2__shard_orders.sql`：建 4 张分片表 → 迁移旧数据 → 删旧表）；
- 分片路由由 `ShardingRoutingTest`（H2 + 真实 driver）验证，同时覆盖 undo_log 穿透；
- user-service 的 Canal 消费端同步改为监听 `seata_order.orders_0 ~ orders_3`（`TableSyncHandler.supportedKeys()` 支持多 key）。

**关键点**：5.5.x 的 ShardingSphere 不会自动发现未声明的表——任何未配置分片的表（如 `undo_log`）都必须由 `!SINGLE` 规则显式声明，否则访问即抛 `TableNotFoundException`。

---

## 二、启动报错：undo_log 不存在 / Flyway 顺序

### Q1. 启动报 `Table or view 'undo_log' does not exist`，但库里明明有 undo_log；Flyway 最新版本只有 orders 的修改，为什么和迁移顺序有关？

**结论**：报错发生在**数据源初始化阶段**，早于 Flyway `migrate` 执行：

- Seata 客户端初始化时会对数据源执行同款检查 SQL（`SELECT 1 FROM undo_log LIMIT 1`），此时 Flyway 还没跑完迁移；
- 更关键的是 ShardingSphere 5.5.x 的元数据里**没有未声明表**，检查 SQL 打到 ShardingSphere 后直接报表不存在/无法路由。

两个修复缺一不可：

1. `undo_log` 表由 `V1__init.sql` 建好（Flyway 幂等）；Flyway 用 `spring.flyway.url` **直连物理库**执行 DDL，不走 ShardingSphere 逻辑表路由；
2. `shardingsphere.yaml` 配 `!SINGLE` 规则（`tables: "*.*"`，`defaultDataSource: ds0`），让未分片表穿透到物理库。

### Q2. `FlywayFirstConfiguration` 这个类有用吗？

**结论**：没有用。它只是早期尝试控制 Flyway 启动顺序的残留，现已删除，启动顺序问题由"Flyway 直连物理库 + `!SINGLE` 规则"真正解决。

---

## 三、Seata 与 ShardingSphere 不兼容

### Q1. 去掉 `@GlobalTransactional` 程序正常，保留就报：

```text
DBC Connection [...ConnectionProxy@...] will not be managed by Spring
Preparing: INSERT INTO orders (id, user_id, product_id, count) VALUES (?, ?, ?, ?)
ERROR AbstractTableMetaCache - get table meta of the table `orders` error: Failed to fetch schema of orders
```

**结论**：这是 Seata 2.x 自动代理和 ShardingSphere 逻辑表的根本冲突：

- Seata 的 `SeataAutoDataSourceProxyCreator` 会把 ShardingSphereDataSource 包成 `DataSourceProxy`；
- AT 模式生成 undo log 时用 JDBC `DatabaseMetaData` 查**逻辑表 `orders`** 的元数据；
- 而 ShardingSphere 元数据里只有物理表 `orders_0 ~ orders_3`，查不到 `orders` → "Failed to fetch schema of orders"。

### Q2. 官方兼容方案是什么？

**结论**：ShardingSphere 5.5.3 提供官方 Seata AT 集成（`shardingsphere-transaction-base-seata-at`），要点：

1. 依赖：引入 `org.apache.shardingsphere:shardingsphere-transaction-base-seata-at:5.5.3`（Seata 客户端需 2.5.0+，本项目是 2.5.0）；按官方要求排除 `seata-all` 传递的旧版 `antlr4-runtime`；
2. `shardingsphere.yaml` 增加：
   ```yaml
   transaction:
     defaultType: BASE
     providerType: Seata
   ```
3. classpath 增加 `seata.conf`（`shardingsphere.transaction.seata.at.enable=true`、`tx.timeout=60`、`client.application.id=order-service`、`transaction.service.group=seata_tx_group`）；
4. `application.yml` 设 **`seata.enable-auto-data-source-proxy: false`**（防止 Seata 再包一层代理，这是崩溃根源）；
5. **禁止在分片数据源上用 `@GlobalTransactional`**，业务方法改用 Spring `@Transactional`（`TransactionTemplate`、`jakarta.transaction.Transactional` 同样允许）。

**XID 传播**：官方集成的 `SeataATShardingSphereTransactionManager` 把 XID 写入 Seata `RootContext`（线程本地），
因此 Feign/Dubbo 原有的 XID 传递链路（`SeataFeignInterceptor` / seata-dubbo filter）无需改动，stock-service 照常作为分支参与方。

---

## 四、Java 21 启动报错：javax.transaction 缺失

### Q1. 加了 transaction 规则后启动报：

```text
ServiceConfigurationError: ...XAShardingSphereTransactionManager Unable to get public no-arg constructor
Caused by: NoClassDefFoundError: javax/transaction/SystemException
```

**结论**：ShardingSphere 构建事务规则时会通过 SPI **预加载全部** `ShardingSphereDistributedTransactionManager` 实现（包括用不到的 XA），
而 `shardingsphere-transaction-xa-core`（由 `shardingsphere-jdbc` 传递引入）的类签名引用旧版 JTA API `javax.transaction.*`：

- JDK 11+ 已从 JDK 移除 `javax.transaction` 包；
- XA 模块声明的 `jakarta.transaction-api` 提供的是 `jakarta.transaction.*`，救不了 `javax.transaction.*`；
- 于是 ServiceLoader 实例化 XA provider 时 `NoClassDefFoundError`，整个数据源初始化失败。

**修复**：显式引入 `javax.transaction:javax.transaction-api:1.3`，补回 JTA API。
回归测试 `TransactionManagerSpiLoadTest` 直接走 ServiceLoader 实例化 XA 和 BASE(Seata) 两个 provider，防止复发。
此前测试没暴露的原因：测试 YAML 没配 `transaction` 段，不会触发事务规则的 SPI 预加载。

---

## 五、@Transactional 一定走 Seata 吗

### Q1. 我用了 `@Transactional` 就调用了 Seata；如果只是想用本地事务，怎么区分？

**结论**：在 ShardingSphere 5.4+ 里**无法按方法区分**——事务模式是**数据源级全局配置**：

- 4.x 的 `@ShardingSphereTransactionType` / `TransactionTypeHolder` 在 5.4 起被移除（官方 issue #36219、#24694 确认，5.5.3 jar 中已不存在）；
- 连接创建时 `ConnectionTransaction` 的 `transactionType` 优先取连接上下文（新连接恒为空），否则回落 `transaction.defaultType`；
- 所以 `defaultType: BASE` 时，该数据源上的所有 `@Transactional` / `TransactionTemplate` 都是 Seata 全局事务；
- 不加事务注解 = 物理库 autocommit（不碰 Seata，但多语句也没有本地原子性）。

文档里"访问前可切换事务类型"是指 Proxy 模式的 DistSQL 能力，JDBC driver 模式下没有对外的按调用切换入口。

---

## 六、双数据源：如何用注解显式区分 Seata / 本地事务

### Q1. 想要"分布式流程走 Seata、纯本地流程不走 Seata"，怎么落地？

**结论**：拆**两个 ShardingSphere 数据源**，分片规则完全一致（`orders → orders_0~3`，`id % 4`），只是事务语义不同：

| 数据源 | 配置 | 事务语义 | 事务管理器 |
| --- | --- | --- | --- |
| `dataSource`（@Primary） | `shardingsphere.yaml`，`defaultType: BASE` | Seata 全局事务 | `seataTransactionManager`（@Primary） |
| `localDataSource` | `shardingsphere-local.yaml`，`defaultType: LOCAL` | 物理库本地事务 | `localTransactionManager` |

### Q2. 写代码时怎么显式感知是否走了 Seata？

**结论**：两个具名事务管理器 + 按包绑定的 mapper，**注解即显式**：

```java
@Transactional("seataTransactionManager")  // Seata 全局事务，配合 OrderMapper（BASE 数据源）
@Transactional("localTransactionManager")  // 本地事务，不走 Seata，配合 LocalOrderMapper（LOCAL 数据源）
```

不带名字的 `@Transactional` 落到 `@Primary` 的 `seataTransactionManager`（默认就是 Seata，符合"宁可显式也不悄悄退化成 autocommit"的安全方向）。
**事务管理器与 mapper 必须成对使用**：`localTransactionManager` 配 `LocalOrderMapper`；如果错配成 BASE 的 `OrderMapper`，
MyBatis 连接和事务不同源，会退化成逐条 autocommit——所以看见 `LocalOrderMapper` 就必须配 local 管理器。

示例：`OrderMqTransactionListener.executeLocalTransaction`（RocketMQ 事务消息的本地插入）改为
`@Transactional("localTransactionManager")` + `LocalOrderMapper`，本地插入不再产生 Seata 全局事务和 undo_log。

---

## 七、LocalOrderMapper 注册报错与 XML 化

### Q1. 启动报 `BeanInstantiationException: Type interface ...LocalOrderMapper is not known to the MapperRegistry`

**结论**：**注解式 mapper 不会自动注册进 MapperRegistry**。XML 或 `@MapperScan` 会触发注册，但通过
`SqlSessionTemplate.getMapper()` 手动取代理前必须先注册。修复：取代理前 `configuration.addMapper(LocalOrderMapper.class)`。

### Q2. 想把 SQL 从注解改成 XML 维护，怎么调整？

**结论**：

1. 接口去掉 `@Insert`，只留方法签名；
2. 新建 `src/main/resources/mapper-local/LocalOrderMapper.xml`，namespace 为接口全限定名，SQL 与 `OrderMapper.xml` 一致；
3. LOCAL 工厂 `setMapperLocations("classpath*:mapper-local/*.xml")` 显式加载；XML 的 namespace 绑定会自动 `addMapper`，手动注册可删。

**为什么放 `mapper-local/` 而不是 `mapper/`**：BASE 工厂的 mapper-locations 是 `classpath*:mapper/**/*.xml`，
放 `mapper/` 下会被 BASE 工厂也解析一遍，把 LOCAL 的语句注册进 Seata 数据源，职责就混了。

---

## 八、多个 LocalMapper 的批量配置

### Q1. 每个 LocalMapper 都写一个 `buildXxx` 工厂方法太复杂，有没有简单的批量方式？

**结论**：用标准的 **`@MapperScan` + 两个 SqlSessionFactory** 按包绑定：

```java
@MapperScan(basePackages = "com.example.order.mapper", sqlSessionFactoryRef = "sqlSessionFactory")      // BASE（Seata）
@MapperScan(basePackages = "com.example.order.dao",   sqlSessionFactoryRef = "localSqlSessionFactory") // LOCAL（本地）
```

之后新增本地 mapper 的流程是**零配置**：接口放 `com.example.order.dao`、XML 放 `resources/mapper-local/`，自动注册。

**关键约束（反编译验证）**：MyBatis-Plus 自动装配的 `sqlSessionFactory` 是 `@ConditionalOnMissingBean(SqlSessionFactory.class)`（**按类型**），
classpath 上出现任何第二个 SqlSessionFactory Bean 就会整体退避，导致 `@MapperScan` 失去工厂。所以正确的做法是**显式接管两个工厂**：

- BASE 工厂继续从 `MybatisPlusProperties` 读配置（`application.yml` 的 `mybatis-plus.*` 仍有效；注意 3.5.17 的 `getConfiguration()` 返回
  `CoreConfiguration`，要用 `applyTo(new MybatisConfiguration())` 应用）；
- LOCAL 工厂只加载 `mapper-local/*.xml`。

`OrderApplication` 上原来的 `@MapperScan` 已移除，统一收口到 `OrderDataSourceConfig`。

---

## 九、@Mapper 与 @MapperScan 的区别

### Q1. `LocalOrderMapper` 没加 `@Mapper` 怎么也被找到了？`OrderMapper` 就加了。

**结论**：两种注册机制，`@Mapper` 不是 `@MapperScan` 的必需品：

- **显式 `@MapperScan` 默认收包内所有接口**：反编译 `ClassPathMapperScanner.registerFilters()`，未指定
  `annotationClass` / `markerInterface` 时 `acceptAllInterfaces=true`，不需要任何注解；
- **`@Mapper` 是给"没有 @MapperScan 的自动扫描"用的**：Spring Boot 下 MyBatis/MyBatis-Plus 的自动扫描器
  （`AutoConfiguredMapperScannerRegistrar`）以 `@Mapper` 为过滤条件，只注册带注解的接口；
- 本项目两个包都有显式 `@MapperScan`，所以 `OrderMapper` 上的 `@Mapper` 是**冗余的**（无害）；
  自动扫描器也会因为存在显式 `@MapperScan` 而退避，`@Mapper` 实际没被消费。

---

## 十、实测验证清单

1. `mvn -pl order-service -am test`：order-service 30 个用例全部通过（分片路由、undo_log 穿透、SPI 加载、本地工厂注册等）；
2. 重启 order-service（MySQL / Nacos / Seata 先启动）：
   - `/order/create?userId=1&productId=1&count=1&fail=false`：日志出现 Seata 全局事务（XID）、INSERT 路由到 `orders_?`；
   - 同 URL 加 `fail=true`：抛异常并触发**全局回滚**，订单与库存同时回滚；
   - `/order/create3?userId=1&productId=1&count=1`：RocketMQ 半消息触发监听器本地插入，只有 ShardingSphere 路由 SQL，
     无 XID、无 undo_log——证明走 LOCAL 事务；
3. 观察点：两个 YAML 都开着 `sql-show: true`，靠日志区分两条事务路径；`undo_log` 是否有写入也可佐证。

---

## 十一、面试要点速记

- **分片键与路由**：`orders` 按 `id % 4` 分 4 张表；逻辑表 SQL 不变，物理 DDL 交给 Flyway 直连物理库；
- **5.5.x 集成方式**：`ShardingSphereDriver` + `jdbc:shardingsphere:classpath:xxx.yaml`，分片核心按插件显式引入；未分片表必须配 `!SINGLE`；
- **Seata × ShardingSphere**：官方集成三件套 = `shardingsphere-transaction-base-seata-at` 依赖 + YAML `transaction` 段（BASE/Seata）+ `seata.enable-auto-data-source-proxy: false`；**禁用 `@GlobalTransactional`**，改用 Spring `@Transactional`；
- **Java 21 缺 JTA**：`shardingsphere-jdbc` 会带 XA 模块，需补 `javax.transaction:javax.transaction-api:1.3`，否则 SPI 预加载直接崩；
- **5.4+ 事务模式是数据源级配置**：按方法切换事务类型的注解已移除；想同时要本地/分布式语义，就拆两个数据源（BASE + LOCAL）；
- **显式事务约定**：`@Transactional("seataTransactionManager")` 配 `OrderMapper` = Seata；`@Transactional("localTransactionManager")` 配 `LocalOrderMapper` = 本地；错配会退化成 autocommit；
- **批量注册**：`@MapperScan` + 双 SqlSessionFactory 按包绑定；注意 MP 自动装配的 `@ConditionalOnMissingBean(SqlSessionFactory.class)` 会因第二个工厂退避，需显式接管；
- **@Mapper 定位**：只影响"无 @MapperScan 的自动扫描"；有显式 @MapperScan 时接口不需要 @Mapper。
