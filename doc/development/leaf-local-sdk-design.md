# order-service 本地集成 Leaf（leaf-core SDK 方式）设计方案

> 背景：目前 order-service 通过 HTTP 调用独立 Leaf 服务（`http://127.0.0.1:8085`）取 ID，多一层网络。需求：探索"本地调用"方式——把 Leaf 作为库打进业务服务 JVM，去掉 HTTP。
> 前置：`~/tools/leaf` 已按第 15 课构建；MySQL `leaf` 库（`leaf_alloc` 表）与 ZooKeeper 2181 已就绪。

## 1. 结论先行：有本地方式，且官方就是这么设计的

Leaf 官方仓库只有两个模块：

| 模块 | 作用 |
|---|---|
| `leaf-core` | **核心库**：号段 `SegmentIDGenImpl` + 雪花 `SnowflakeIDGenImpl`，纯 JVM 内调用 |
| `leaf-server` | 只是把 leaf-core 包成 HTTP 服务的薄壳（我们当前用的就是它） |

因此"本地调用"= 业务服务直接依赖 `leaf-core`，在自己的 JVM 里调用取号，**不需要 HTTP**。

注意：官方仓库没有 `leaf-boot-starter`（Spring 自动装配模块）；且官方 leaf-server 基于 Spring Boot 1.5，starter 即使存在也难兼容 Spring Boot 3。所以本项目方案采用：**引入 leaf-core + 自写薄封装**（约 60 行），既避开兼容问题，也便于理解原理。

## 2. HTTP 独立服务 vs 本地 SDK 对比

| 维度 | HTTP 独立服务（现状） | 本地 SDK（leaf-core） |
|---|---|---|
| 调用方式 | 每次 HTTP 请求 | JVM 内直接调用（纯内存/本地） |
| 号段模式 | 服务端批量取号，业务端一次 HTTP 换一个 ID | 业务服务自己连 `leaf_alloc` 表批量取号，本地发号 |
| 雪花模式 | 每次取号一次 HTTP RTT | 本地位运算，无网络开销 |
| 故障面 | Leaf 服务挂 → 所有业务取号失败 | 各服务独立，互不影响 |
| 依赖 | 业务只依赖 Leaf 地址 | 每个业务服务都要配 MySQL(leaf 库) + ZooKeeper |
| 运维 | 集中管理（一套号段表/一个服务） | 分散（每服务各自连 DB/ZK，版本升级要跟着改） |
| 一致性 | 无（服务端串行） | 号段靠 DB `UPDATE ... SET max_id=max_id+step` 原子性保证多实例不重叠；雪花靠 ZK 分配不同 workerId |
| 适用 | 中大型团队、统一治理 | 单机/小规模、性能敏感、想少一层网络 |

生产上互联网公司主流仍是**独立 HTTP 服务**（统一治理、监控、故障面隔离）；本地 SDK 适合对性能和部署简化有强诉求的场景。

## 3. 方案设计

### 3.1 依赖（order-service pom）

1. 先把 `leaf-core` 安装到本地 Maven 仓库（一次性的，`~/tools/leaf` 已 clone）：
   ```bash
   cd ~/tools/leaf
   mvn -pl leaf-core install -DskipTests -Dmaven.compiler.source=1.8 -Dmaven.compiler.target=1.8
   ```
2. order-service 引入 `com.sankuai.inf.leaf:leaf-core:1.0.1`，并**排除两个旧版本传递依赖**：

   | leaf-core 传递依赖 | 为什么排除 | 替代 |
   |---|---|---|
   | `org.mybatis:mybatis:3.3.0` | 与项目 mybatis-plus 3.5.x 冲突 | 用项目现有 mybatis 3.5.x（API 兼容） |
   | `mysql:mysql-connector-java:5.1.38` | 与 MySQL 8 不兼容（第 15 课已验证） | 用项目现有 `mysql-connector-j` 8.x |

3. leaf-core 把 `curator-recipes` 标为 **provided**（由使用方提供），order-service 需自行声明：
   ```xml
   org.apache.curator:curator-recipes:2.13.0
   org.apache.zookeeper:zookeeper:3.7.2        <!-- 与本机 ZK 3.7.2 一致 -->
   ```
   （leaf-core 雪花模式用 curator 连 ZK，不依赖 zkclient；Spring 相关依赖全是 test scope，不会与 Spring 6 冲突。）

4. **必须在业务服务 classpath 提供 `leaf.properties`**（放 `src/main/resources/`）：leaf-core 的 `PropertyFactory` 静态初始化会从 classpath 根部加载它（缺失直接 NPE），`SnowflakeZookeeperHolder` 还用 `leaf.name` 拼 ZK 路径 `/snowflake/{leaf.name}/forever`。内容至少包含 `leaf.name`、`leaf.snowflake.*`；号段 JDBC 配置本地 SDK 实际由 `leaf.local` 专用数据源提供，properties 里保持一致便于排查。

### 3.2 配置（application.yml）

保留现有 `leaf.url`（HTTP 模式），新增 `leaf.mode` 与 `leaf.local`：

```yaml
leaf:
  mode: http            # http（默认，走独立 Leaf 服务） | local（本地 SDK）
  url: http://127.0.0.1:8085
  connect-timeout: 2s
  read-timeout: 3s
  local:
    jdbc-url: jdbc:mysql://127.0.0.1:3306/leaf?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: ""
    zk-address: 127.0.0.1:2181
    port: 8083          # 雪花模式注册节点用，填本服务端口
```

### 3.3 组件

```
LeafIDController（接口不变：/leaf/segment、/leaf/snowflake）
   └─ LeafIdGenerator（新增接口：long segmentId(key) / long snowflakeId(key)）
         ├─ HttpLeafIdGenerator   （@ConditionalOnProperty leaf.mode=http, matchIfMissing=true）
         │     复用现有 LeafIdClient（内部 RestTemplate 调 Leaf :8085）
         └─ LocalLeafIdGenerator  （@ConditionalOnProperty leaf.mode=local）
                ├─ Segment: SegmentIDGenImpl + IDAllocDaoImpl(leafDataSource)
                │      leafDataSource = 专用 HikariDataSource（连 leaf 库，独立于 seata_order 主数据源）
                └─ Snowflake: SnowflakeIDGenImpl(zkAddress, port)
```

**LocalLeafIdGenerator 关键实现**：

- 启动时（`@PostConstruct`/工厂方法）调用 `idGen.init()`，DB/ZK 不可用 → **启动快速失败**（ID 是基础能力，不静默降级）；
- `leaf-core` 的 `IDGen.get(key)` 返回 `Result{id, status}`，`status=EXCEPTION` → 抛 `BusinessException(INTERNAL_ERROR)`，与 HTTP 模式错误口径一致；
- `SegmentIDGenImpl` 内部线程安全（双 buffer 预取），业务并发取号无需加锁；
- 雪花模式 `SnowflakeIDGenImpl(zkAddress, port)`：port 传本服务端口，ZK 顺序节点序号即 workerId（多实例自动区分）。

### 3.4 多实例正确性（面试点）

- **号段模式**：多实例同时启动时，各自执行 `UPDATE leaf_alloc SET max_id = max_id + step`，靠数据库行锁保证拿到的号段不重叠——这是号段模式能多实例部署的根本；
- **雪花模式**：每实例启动时到 ZK `/snowflake/forever` 注册顺序节点，拿到不同 workerId，避免同毫秒同序列撞号。

### 3.5 与现有代码的关系

- `LeafIDController` 只改注入对象（`LeafIdClient` → `LeafIdGenerator`），**对外接口与返回结构不变**；
- HTTP 模式实现保留（默认），`leaf.mode=local` 一键切换，方便课堂对比两种方式的差别；
- 现有 `LeafIdClient`/`LeafProperties`/`RestTemplateConfig` 全部保留，仅新增本地实现与接口。

## 4. 测试方案

1. `LocalLeafIdGeneratorTest`：mock `IDGen`（leaf-core 接口），断言成功返回、`EXCEPTION` 状态转 `BusinessException`；
2. `LeafIDControllerTest`：注入 `LeafIdGenerator` mock，接口行为不变（现有 3 个用例微调注入对象即可）；
3. 端到端验证（`leaf.mode=local`）：
   ```bash
   bash scripts/start-zookeeper.sh
   # MySQL leaf 库已就绪
   curl 'http://127.0.0.1:8083/leaf/segment'      # 号段模式，本地内存发号
   curl 'http://127.0.0.1:8083/leaf/snowflake'    # 雪花模式，ZK 分配 workerId
   ```
   验证号段连续性：连取多个 ID 单调递增；验证 ZK 节点：`/snowflake/forever` 下出现本服务节点。

## 5. 实现步骤（方案通过后执行）

1. `mvn -pl leaf-core install`（一次性，写入 ~/.m2）；
2. order-service pom：引入 leaf-core（排除 mybatis/mysql 旧版）+ curator-recipes + zookeeper；
3. 新增 `LeafIdGenerator` 接口、`HttpLeafIdGenerator`（适配现有 LeafIdClient）、`LocalLeafIdGenerator`、`LeafLocalProperties`；
4. `LeafIDController` 改注入接口；`application.yml` 增加 `leaf.mode` / `leaf.local`；
5. 更新/新增测试，`mvn -pl order-service -am test` 全量通过；
6. 手工验证 http 与 local 两种模式。

## 6. 涉及文件

| 文件 | 说明 |
|---|---|
| `order-service/pom.xml` | 引入 leaf-core + curator + zookeeper（含排除） |
| `order-service/.../config/LeafProperties.java` | 增加 mode、local 段（或新增 LeafLocalProperties） |
| `order-service/.../client/LeafIdGenerator.java` | 统一取号接口 |
| `order-service/.../client/HttpLeafIdGenerator.java` | 现有 LeafIdClient 适配为接口实现 |
| `order-service/.../client/LocalLeafIdGenerator.java` | leaf-core 本地实现（Segment + Snowflake） |
| `order-service/.../controller/LeafIDController.java` | 改注入接口，接口不变 |
| `order-service/src/test/...` | 新增 LocalLeafIdGeneratorTest，微调 Controller 测试 |

## 7. 风险与说明

- **版本冲突**：mybatis 3.3 → 排除用 3.5.x；mysql 5.1.38 → 排除用 8.x；curator 由 provided 变显式声明（2.13.0）；
- **ZooKeeper 依赖**：引入 zookeeper 客户端 3.7.2，与本地 ZK 服务端一致；不影响其他服务（order-service 此前无 ZK 依赖）；
- **本地模式启动即依赖 DB/ZK**：适合学习演示；生产若选本地 SDK，建议 DB/ZK 高可用 + 启动自检与监控告警。

### 7.1 落地时踩过的两个坑（已修复，务必保留在代码里）

1. **curator 版本冲突**：父 BOM（dubbo 等）把 curator 管理成 5.8.0，而 leaf-core 用 curator 2.x API（`creatingParentsIfNeeded()`），启动报 `NoSuchMethodError`。修复：order-service 的 `<dependencyManagement>` 显式钉住 curator-recipes/framework/client **2.13.0**（模块级，不影响其他服务）。
2. **leaf 专用数据源不能注册为 Spring Bean**：如果 `leafDataSource` 注册成 Bean，Spring Boot 的 `DataSourceAutoConfiguration` 会因"已有 DataSource"而跳过主数据源装配，导致 Flyway/MyBatis 全部误连 leaf 库（甚至把 orders/undo_log 建进 leaf 库）。修复：在 `LeafLocalConfig` 的 `@Bean` 方法内部创建 HikariDataSource（仅给 `IDAllocDaoImpl` 用），不暴露为 Bean。

### 7.2 必需资源文件

- `order-service/src/main/resources/leaf.properties` 必须存在：leaf-core 的 `PropertyFactory` 静态初始化从 classpath 加载它（缺失直接 NPE），`leaf.name` 决定雪花模式 ZK 路径。
