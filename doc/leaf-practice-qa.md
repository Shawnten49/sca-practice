# Leaf 实践问答全记录

> 适用范围：sca-practice 项目（order-service 接入美团 Leaf，HTTP 与本地 SDK 双模式）。
> 本文汇总 Leaf 接入过程中的选型、原理、依赖排障问答，全部结论基于本机实测（MySQL 8.4 / ZooKeeper 3.7.2 / Leaf 1.0.1）。
> 实现细节见 [leaf-local-sdk-design.md](development/leaf-local-sdk-design.md)。

## 目录

1. [环境搭建与踩坑](#一环境搭建与踩坑)
2. [HTTP 与本地（SDK）选型](#二http-与本地sdk选型)
3. [号段模式正确性](#三号段模式正确性)
4. [依赖与版本排障](#四依赖与版本排障)
5. [实测结果](#五实测结果)
6. [面试要点速记](#六面试要点速记)

---

## 一、环境搭建与踩坑

> 本机环境：macOS（Apple Silicon）+ JDK 21 + MySQL 8.4 + ZooKeeper 3.7.2。Leaf 官方 leaf-server 是 Spring Boot 1.5 的老项目，直接跑会踩一串坑，下面按"下载构建 → 数据库 → 配置 → ZooKeeper → 启动 → 验证"完整走一遍。

### 环境1. 下载与构建（JDK 21 适配）

```bash
git clone --depth 1 https://github.com/Meituan-Dianping/Leaf.git ~/tools/leaf
cd ~/tools/leaf

# 坑①：pom 硬编码 source/target 1.7，JDK 21 已不支持 1.7，必须先改成 1.8
sed -i '' 's|<source>1.7</source>|<source>1.8</source>|;s|<target>1.7</target>|<target>1.8</target>|' pom.xml

# 构建（同时覆盖 MySQL8 兼容依赖，见"环境5"）
mvn -pl leaf-server -am clean package -DskipTests \
    -Dmysql-connector-java.version=8.0.33 -Ddruid.version=1.2.21
# 产物: leaf-server/target/leaf.jar（注意：不是 leaf-server-1.0.1.jar）
```

坑②：本地 SDK 方式引入 `leaf-core` 时，它的 POM 引用父模块 `leaf-parent`，需先安装父 POM 和 leaf-core：

```bash
cd ~/tools/leaf
mvn -N install -DskipTests                                # 安装 leaf-parent 父 POM
mvn -pl leaf-core install -DskipTests \
    -Dmaven.compiler.source=1.8 -Dmaven.compiler.target=1.8   # 安装 leaf-core
```

### 环境2. 初始化 MySQL 号段表

```sql
CREATE DATABASE IF NOT EXISTS leaf DEFAULT CHARACTER SET utf8mb4;
USE leaf;

CREATE TABLE IF NOT EXISTS leaf_alloc (
  biz_tag     VARCHAR(64)  NOT NULL DEFAULT '',
  max_id      BIGINT       NOT NULL DEFAULT 1,
  step        INT          NOT NULL,
  description VARCHAR(256) DEFAULT NULL,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
  ('order_id', 1, 1000, '订单号段'),
  ('user_id',  1, 1000, '用户号段');
```

### 环境3. 配置 leaf.properties

改 `leaf-server/src/main/resources/leaf.properties`（本地 SDK 方式则放到业务服务 `src/main/resources/`），同时开启两种模式：

```properties
leaf.name=com.sankuai.leaf.opensource.test

# 号段模式
leaf.segment.enable=true
leaf.jdbc.url=jdbc:mysql://127.0.0.1:3306/leaf?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai
leaf.jdbc.username=root
leaf.jdbc.password=

# 雪花模式
leaf.snowflake.enable=true
leaf.snowflake.zk.address=127.0.0.1:2181
leaf.snowflake.port=8085
```

注意：`leaf.name` 决定雪花模式在 ZK 的注册路径 `/snowflake/{leaf.name}/forever`；`leaf.snowflake.port` 填 Leaf 服务自身端口。

### 环境4. 安装并启动 ZooKeeper（两个坑）

```bash
# 坑③：brew install zookeeper 卡在镜像下载，改用 Apache 归档（与 Seata/Canal 一致的方式）
curl -sL -o /tmp/zk.tar.gz https://archive.apache.org/dist/zookeeper/zookeeper-3.7.2/apache-zookeeper-3.7.2-bin.tar.gz
tar -xzf /tmp/zk.tar.gz -C ~/tools && mv ~/tools/apache-zookeeper-3.7.2-bin ~/tools/zookeeper
cp ~/tools/zookeeper/conf/zoo_sample.cfg ~/tools/zookeeper/conf/zoo.cfg

# 坑④：ZK 3.7 默认开 AdminServer（Jetty 监听 8080），本机 8080 被占用会启动失败 → 关闭它
echo 'admin.enableServer=false' >> ~/tools/zookeeper/conf/zoo.cfg

# 启动（前台方式便于看日志；或 bash scripts/start-zookeeper.sh）
cd ~/tools/zookeeper && bin/zkServer.sh start-foreground
```

### 环境5. 启动 Leaf（Spring Boot 1.5 + JDK 21 三连坑）

```bash
cd ~/tools/leaf/leaf-server
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
     --add-opens java.base/java.net=ALL-UNNAMED \
     --add-opens java.base/java.io=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar target/leaf.jar --server.port=8085
# 或 bash scripts/start-leaf.sh
```

| 坑 | 现象 | 修复 |
|---|---|---|
| 坑⑤ Spring Boot 1.5 + JDK 21 | 启动报 `InaccessibleObjectException`（CGLIB 反射 `java.base` 模块被拒） | 启动加 7 个 `--add-opens java.base/...=ALL-UNNAMED` |
| 坑⑥ mysql-connector 5.1.38 | 连 MySQL 8 握手报 `NullPointerException: serverVariables is null` | 构建时 `-Dmysql-connector-java.version=8.0.33` |
| 坑⑦ druid 1.0.18 | 连接校验一直 `validateConnection false`（认不出新驱动类） | 构建时 `-Ddruid.version=1.2.21` |

启动日志出现这几行即就绪：

```text
Segment Service Init Successfully
Snowflake Service Init Successfully
START SUCCESS USE ZK WORKERID-0
Tomcat started on port(s): 8085 (http)
```

### 环境6. 验证与一键脚本

```bash
curl http://127.0.0.1:8085/api/segment/get/order_id      # 号段模式
curl http://127.0.0.1:8085/api/snowflake/get/leaf        # 雪花模式
curl 'http://127.0.0.1:8085/decodeSnowflakeId?snowflakeId=...'  # 解码
```

配套脚本（自动探测、已运行则重启、就绪等待）：

```bash
bash scripts/start-zookeeper.sh   # ZooKeeper :2181
bash scripts/start-leaf.sh        # Leaf      :8085
```

---

## 二、HTTP 与本地（SDK）选型

### Q1. Leaf 有本地调用方式吗？

**有**。Leaf 官方仓库只有两个模块：

| 模块 | 作用 |
|---|---|
| `leaf-core` | 核心库：号段 `SegmentIDGenImpl` + 雪花 `SnowflakeIDGenImpl`，JVM 内直调 |
| `leaf-server` | 把 leaf-core 包成 HTTP 服务的薄壳（独立部署的形态） |

所以"本地调用"= 业务服务直接依赖 `leaf-core`，去掉 HTTP 层。官方没有兼容 Spring Boot 3 的 starter，本项目做法是**引入 leaf-core + 自写薄封装**（约 60 行）：

- `LeafIdGenerator` 接口（`segmentId(key)` / `snowflakeId(key)`）；
- `HttpLeafIdGenerator`（默认，`leaf.mode=http`，走独立 Leaf 服务）；
- `LocalLeafIdGenerator`（`leaf.mode=local`，JVM 内直调 leaf-core）；
- `LeafLocalConfig` 装配：号段用专用数据源连 `leaf` 库，雪花连 ZooKeeper 分配 workerId。

### Q2. 真实业务用 HTTP 还是 local？

**主流是集中式独立服务（HTTP/RPC 调用），不是本地 SDK。** 美团官方 leaf-server 就是这个形态。

| 维度 | HTTP 独立服务（主流） | 本地 SDK |
|---|---|---|
| 治理 | 号段表一套、配额/监控/告警集中 | 每服务各自连 DB/ZK，分散 |
| 故障面 | Leaf 挂了业务快速感知/降级 | 任何实例的 DB/ZK 抖动都放大成全局问题 |
| 运维 | 只升级一个服务 | 每个服务跟着升级 leaf-core |
| 性能 | 号段模式一次取 1000 个，HTTP 开销被摊薄 | 纯本地，延迟最低 |
| 依赖面 | 业务只依赖 Leaf 地址 | 业务要配 MySQL + ZooKeeper |

**local 的适用场景**：对取号延迟极端敏感（雪花每次调用都走网络）、不想引入独立服务、单机/原型/学习。即便走本地，生产上也通常由中间件团队封装好 workerId 分配与号段高可用再下发（参考百度 UidGenerator）。

> **面试一句话**：问题本质不是 HTTP vs local，而是**集中式 ID 服务 vs 内嵌 SDK**；真实生产选前者，接口协议 HTTP/Dubbo/gRPC 都可以。

### Q3. 什么业务场景用号段模式，什么场景用雪花算法模式？

一句话：**需要"连续、可管理、可对账"的编号用号段；需要"高性能、防枚举、无状态"的内部主键用雪花。**

| 维度 | 号段模式 | 雪花模式 |
|---|---|---|
| 递增性 | 严格递增、连续，区间可预测 | 趋势递增、有跳跃，不可预测 |
| 可枚举性 | 可被猜测（暴露业务量、易遍历） | 19 位大数，无法反推业务量 |
| 性能/依赖 | 依赖 MySQL，换段时有 DB 访问 | 纯位运算，无 DB 依赖 |
| 可管理性 | 区间可查、按段预分配/对账 | 无区间概念 |
| 多实例 | 靠 DB 行锁保证段不重叠 | workerId 由 ZK 分配，天然水平扩展 |

典型场景：

| 场景 | 推荐 | 原因 |
|---|---|---|
| 发票流水号、合同编号、工单号 | 号段 | 必须连续、可审计、客服能念出来 |
| 快递运单号、兑换码/卡券批次号 | 号段 | 按段预分配、批次对账、区间管理 |
| 资金/交易流水号 | 号段 | 对账友好（区间可查、可补号） |
| 电商订单主键（对外） | 雪花 | 防枚举、不泄露业务量 |
| 用户 ID、会员号（对外） | 雪花 | 不可猜测注册量 |
| 消息/事件 ID、日志 traceId | 雪花 | 高吞吐、无状态 |
| 分库分表全局主键 | 雪花 | 高性能、趋势递增（配合 ShardingSphere） |

常见误区：以为"订单号必须用号段"。实际上**对外订单号用雪花更专业（防枚举是硬需求）**；发票号、运单号这类监管/对账场景才必须用号段。落到本项目：`order_id` 走雪花，若以后加"工单/流水号"功能走号段（新 biz_tag）。

> 面试一句话：号段模式是"可管理的连续编号"，适合监管/对账/批次场景；雪花模式是"高性能防枚举的内部主键"，适合订单、用户、消息等海量唯一 ID——判断标准看编号是"给人看/要连续"还是"给系统用/要安全"。

### Q4. snowflakeId(key) 里这个 key 有什么作用？

**雪花模式中 key 完全不参与 ID 生成，传什么值结果都一样。** 看 `SnowflakeIDGenImpl.get(key)` 源码：

```java
@Override
public synchronized Result get(String key) {
    long timestamp = timeGen();
    // 时钟回拨处理、序列号递增……
    long id = ((timestamp - twepoch) << timestampLeftShift)
            | (workerId << workerIdShift) | sequence;
    return new Result(id, Status.SUCCESS);
}
```

方法体从头到尾**没有用到 key**，ID 只由**时间戳 + workerId + 序列号**决定。

**为什么接口还要这个参数**：`IDGen` 接口是号段/雪花共用的（`Result get(String key)`）——号段模式用 key 当 `biz_tag`（决定从哪条号段取号），雪花模式忽略它，属于接口统一的"历史包袱"。HTTP 接口 `/api/snowflake/get/{key}` 同理，key 只是路径参数（路由/日志），传 `leaf`、`order_id`、任意字符串结果都一样（可用不同 key 连续调用实测验证）。

顺带细节：源码里雪花发号做了序列号随机化——每个新毫秒开始时 `sequence = RANDOM.nextInt(100)`，同一毫秒内 ID 也是"大致递增带随机跳跃"，进一步说明与 key 无关。

**如果业务想区分不同类型的 ID**：不要在 key 上做文章（没用），在业务层用字段/表区分（如订单 ID 与流水 ID 各存各的），雪花只管"全局唯一 + 趋势递增"。

---

## 三、号段模式正确性

### Q3. 怎么确保不会取到重复的号段？

核心：**取号段用一条原子的 `UPDATE`（InnoDB 行锁），每个实例拿到的区间是从数据库"抢"来的独占区间，先到先得、互不重叠**。

```sql
-- 1. 原子推进：对 leaf_alloc 该行加排他锁，max_id 前移 step
UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = 'order_id';

-- 2. 读回新值，拿到独占区间 (旧max_id, 新max_id]
SELECT biz_tag, max_id, step FROM leaf_alloc WHERE biz_tag = 'order_id';
```

并发时间线（三个实例同时取号）：

| 实例 | UPDATE 结果 | 独占区间 |
|---|---|---|
| A | max_id 1 → 1001 | (1, 1000] |
| B | max_id 1001 → 2001 | (1001, 2000] |
| C | max_id 2001 → 3001 | (2001, 3000] |

- 并发 UPDATE 被 InnoDB 行锁串行化，B 必须等 A 提交后才执行，读到的 max_id 一定是 1001；
- **不能"先 SELECT 再 UPDATE"**：两个实例会读到同一个 max_id 导致重复——必须"先 UPDATE 占位、再 SELECT 读区间"；
- 实例崩溃：区间没发完就浪费（空洞），但**不会重复**（max_id 已推进）；
- 取段必须走**主库**，主从延迟不影响正确性；
- 双 buffer 预取只是提前用同一机制占下一段，依然全局不重叠；
- 多机房扩展：按机房拆多套 `leaf_alloc` 表（如 `leaf_alloc_zone1/zone2`），各机房独立取号。

### Q4. 多服务 A、B 都 HTTP 调 Leaf，怎么保证拿到不同号？A 下次调用是 last+1 吗？

**先澄清一个关键概念**：HTTP 架构下，A、B 根本不直接"拿段"——**段只存在于 Leaf 服务端内存里，A、B 每次调用只是从服务端"撕走一个号"**。

**Q4a：A、B 怎么保证不撞号？**

- 单 Leaf 实例：`SegmentIDGenImpl` 从内存段按序发号，天然不重复；
- 多 Leaf 实例：多实例都执行原子 UPDATE + 行锁，段区间不重叠（Q3 的机制），A 打到实例1、B 打到实例2 也不撞。

**Q4b：A 下次调用一定拿到 last+1 吗？**

**不一定**，分三种情况：

| 场景 | 结果 |
|---|---|
| 只有 A 一个调用方 | 严格 +1（段内按序，跨段连续，如 1000 → 1001） |
| A、B 并发调用 | 不是 A 的 last+1，而是**全局下一个号**（A 拿 1001，B 拿 1002，A 再拿 1003……） |
| 多 Leaf 实例 | 同一实例内连续；**跨实例时连"全局严格递增"都不保证**（实例2 可能先发 1001，实例1 后发 1000），但唯一性不受影响 |

> **面试/设计要点**：号段模式保证的是**全局唯一 + 趋势递增（单实例下严格递增）**，不是"每个调用方各自的连续性"。若业务要求 A、B 各自连续，给它们分配**不同的 biz_tag**（A 用 `order_id`，B 用 `user_id`）。

---

## 四、依赖与版本排障

### Q5. Curator 是什么？钉回 2.13.0 会影响 Dubbo 吗？

**Curator 是 ZooKeeper 的 Java 高层客户端库**：

| 模块 | 职责 |
|---|---|
| `curator-client` | 底层连接管理：会话、自动重连、重试策略 |
| `curator-framework` | 流畅 API：节点 CRUD、Watcher、事务 |
| `curator-recipes` | 分布式原语：分布式锁、选主、队列等 |

项目里的两个潜在使用者：**Leaf**（`SnowflakeZookeeperHolder` 连 ZK 注册 workerId + 时钟校验）和 **Dubbo 的 zookeeper 注册中心**（如果配置 zk registry）。

**钉回 2.13.0 不会影响本项目 Dubbo**：

1. order-service 依赖树只有 `dubbo-registry-nacos`，**没有 `dubbo-registry-zookeeper`**——Dubbo 用 Nacos 注册中心，运行时根本不加载 curator 代码；
2. 即使以后切 zookeeper 注册中心，curator 2.13.0 + zookeeper 3.7.2 客户端这套组合已被 Leaf 雪花模式实测跑通，Dubbo 的基础节点操作 API 完全兼容；
3. 钉版本写在 **order-service 模块级 `<dependencyManagement>`**，不影响 user-service / stock-service 等其他服务。

**为什么 5.8.0 会冒出来**：父 BOM（dubbo starter 自带）把 curator 统一管理成 5.8.0；`curator-recipes:2.13.0` 的 POM 里 `curator-framework` 没写版本号，Maven 按父 BOM 补成 5.8.0，形成"recipes 2.13 + framework 5.8"混搭，leaf-core 编译用的 2.x API（`creatingParentsIfNeeded()`）就找不到方法了。模块级钉住 2.13.0 全家桶即修复。

> 留意边界：若未来 Dubbo 升级到强制要求 curator 5.x 新 API 的版本，或引入其他依赖 curator 5.x 的组件，需重新评估；正解是升级/替换 Leaf 的 ZK 依赖，而不是迁就它降其他组件。

### Q6. 本地集成踩过的坑（3 个）

| 坑 | 现象 | 修复 |
|---|---|---|
| 缺少 `leaf.properties` | 启动报 `PropertyFactory` 静态初始化 NPE（`inStream parameter is null`） | 在 `src/main/resources/` 提供 `leaf.properties`（`leaf.name` 还决定雪花模式 ZK 路径 `/snowflake/{leaf.name}/forever`） |
| curator 版本冲突 | 启动报 `NoSuchMethodError: CreateBuilder.creatingParentsIfNeeded()` | order-service `dependencyManagement` 钉 curator-recipes/framework/client 2.13.0 |
| leaf 数据源注册成 Bean | Flyway/MyBatis 误连 leaf 库，甚至把 orders/undo_log 建进 leaf 库 | 数据源在 `@Bean` 方法内部创建（仅给 `IDAllocDaoImpl` 用），**不注册为 Spring Bean**，避免顶掉主数据源装配 |

---

## 五、实测结果

### 本地模式（`leaf.mode=local`，独立端口 18083 验证后已停止）

```text
启动日志：
  Leaf segment 本地初始化成功
  START SUCCESS USE ZK WORKERID-1   （雪花模式，ZooKeeper 分配 workerId）
  Flyway Database: .../seata_order  （主数据源正常，未被 leaf 数据源顶替）

接口：
  GET /leaf/segment      → 3001, 3002, ..., 3006（连续递增，本地内存发号）
  GET /leaf/snowflake    → 2089037315115585542 → 递增
  leaf_alloc: order_id max_id=4001（第 4 段 3001–4000 已预取）
```

### 测试

`mvn -pl order-service -am test`：23/23 通过（含 `LocalLeafIdGeneratorTest` 4 个用例：成功 / EXCEPTION 转 BusinessException / 空 key 转 400）。

---

## 六、面试要点速记

1. **选型**：主流是集中式 ID 服务（HTTP/RPC）；local SDK 只用于延迟极敏感/单机/学习；
2. **号段不重复**：原子 `UPDATE max_id = max_id + step` + InnoDB 行锁，"先占位再读区间"，先到先得互不重叠；
3. **服务层视角**：段在服务端内存，A/B 每次调用拿一个号；单调用方严格 +1，并发下是全局下一个号，多实例下"大致递增"；
4. **按调用方连续**：给不同服务分配不同 `biz_tag`；
5. **Curator**：ZooKeeper 高层客户端；本项目 Dubbo 用 Nacos，钉 2.13.0 无影响；
6. **本地集成三要素**：`leaf.properties` 必带、curator 版本对齐 leaf-core、leaf 数据源不注册为 Bean。
7. **模式选型**：号段=连续可管理（发票/运单/流水），雪花=防枚举内部主键（订单/用户/消息）；雪花 `get(key)` 的 key 不参与生成，只是接口共用参数。
