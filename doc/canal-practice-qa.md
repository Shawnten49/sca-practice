# Canal 学习与实践问答全记录

> 适用范围：sca-practice 项目（Spring Boot 3.5 / Spring Cloud Alibaba 2025.0 / MySQL 8.4 / RocketMQ 5.5.0）。
> 本文把从"为什么要用 Canal"到"怎么搭、怎么消费、怎么保证幂等与字段级过滤、踩了哪些坑"的完整问答整理成学习资料，可直接用于复习和面试。

## 目录

1. [Canal 是什么与选型](#一canal-是什么与选型)
2. [环境搭建与 Canal Admin](#二环境搭建与-canal-admin)
3. [Canal Server 接入 Admin（manager 模式）](#三canal-server-接入-adminmanager-模式)
4. [消息格式与 binlog 位点](#四消息格式与-binlog-位点)
5. [消费端架构与路由分发](#五消费端架构与路由分发)
6. [幂等与去重](#六幂等与去重)
7. [字段级变更过滤](#七字段级变更过滤)
8. [乱序问题](#八乱序问题)
9. [监听粒度与配置管理](#九监听粒度与配置管理)
10. [故障排查与踩坑记录](#十故障排查与踩坑记录)
11. [面试要点速记](#十一面试要点速记)

---

## 一、Canal 是什么与选型

### Q1. 什么时候才需要 Canal？业务代码里改完数据库直接发 RocketMQ 不行吗？

**结论**：多数"业务事件"场景直接用 RocketMQ 就够了（甚至配合事务消息保证一致性），Canal 不是替代品，而是覆盖**业务代码看不到、不适合、管不住**的那部分数据变更。

**核心区别**：

| 维度 | 业务代码发 MQ（业务事件） | Canal（数据变更） |
|---|---|---|
| 触发点 | 业务代码里"动作发生"时 | 数据库 binlog 里"数据变了"时 |
| 消息内容 | 业务语义 + 上下文（订单号、用户、traceId） | 行数据 before/after |
| 一致性 | 需自己保证（RocketMQ 事务消息 / 本地消息表） | 天然事后观察，不影响主链路 |
| 写库入口 | 必须覆盖所有入口 | 不管谁写的库都能感知 |
| 典型消费方 | 积分、通知、履约等业务服务 | 缓存刷新、ES、数仓、审计 |

**必须用 Canal 的三个场景**：

1. **写库入口不可控**：定时任务、批处理、DBA 手工订正、后台管理平台、其他团队/老系统直连数据库、数据迁移脚本——这些改库动作业务代码完全感知不到，自然不会发 MQ；Canal 挂在 binlog 上，只要是库里的变更就逃不掉。
2. **同步目标是"存储"而不是"业务消费者"**：MySQL → Elasticsearch（搜索）、MySQL → ClickHouse/Doris（数仓）、MySQL → Redis/本地缓存（Cache-Aside 失效刷新）、MySQL → 其他 NoSQL / 异地备份。本质是"库到库/存储的复制"，不该让业务系统替数据管道埋点。
3. **需要 before/after、位点回溯、零侵入**：binlog 天然带变更前后值（审计"谁把 price 从 100 改成 200"）；Canal 支持从任意位点重新消费（全量 + 增量、故障补偿）；消费端挂了不影响主流程写库。

**业务代码直接发 MQ 更合适的场景**：

- 事件有业务语义（订单已支付、用户已注册），消费方关心"发生了什么"而不是"哪行变成了什么"；
- 消息需要聚合多表/计算后的结果（下单后发"订单+商品+用户"完整快照），binlog 是分散的行变更，组装成本高；
- 需要业务上下文（traceId、业务单号做幂等键）；
- 数据库不是 MySQL / 没有 binlog，或云数据库不给开 binlog。

**组合拳（真实系统做法）**：

电商例子：下单成功 → 订单服务**业务代码**发"订单创建事件"给积分/通知服务；同时 **Canal 监听订单表**同步到 ES 和数仓。两条管道各干各的：前者管业务流，后者管数据流。

**面试一句话**：业务 MQ 发的是"业务事件"，Canal 发的是"数据变更事实"。选型看两点——事件的定义是业务动作还是数据变化，以及写库入口是否单一可控；入口不可控或目标是存储时，Canal 是绕不开的答案。

---

## 二、环境搭建与 Canal Admin

### Q2. Canal Admin 是什么？如何下载安装？

**组件关系**：

- **Canal Server（deployer）**：真正干活的进程，解析 binlog 并投递到 MQ；
- **Canal Admin**：Web 管理台（Spring Boot + React），管理 Server 节点与实例（数据库里的配置），默认端口 8089；
- **canal_manager 数据库**：Admin 的配置存储（MySQL），存节点、实例配置、服务端配置。

**安装步骤（macOS，本机实测）**：

1. 下载 `canal.admin-1.1.8.tar.gz`（GitHub Releases），解压到 `~/tools/canal/canal.admin-1.1.8/`；
2. 初始化数据库：

```bash
/opt/homebrew/opt/mysql@8.4/bin/mysql -u root < ~/tools/canal/canal.admin-1.1.8/conf/canal_manager.sql
```

生成 6 张表：`canal_adapter_config`、`canal_cluster`、`canal_config`、`canal_instance_config`、`canal_node_server`、`canal_user`；

3. 修改 `conf/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/canal_manager?useUnicode=true&characterEncoding=UTF-8
    username: root
    password: ""
canal:
  adminUser: admin
  adminPasswd: 123456   # Web 登录密码（明文）
```

4. 用一键脚本启动（与项目 `scripts/` 下其他脚本同款，自动探测/重启/就绪等待）：

```bash
bash scripts/start-canal-admin.sh
```

5. 访问 `http://127.0.0.1:8089`，账号 `admin` / `123456`。

### Q3. canal.admin.passwd 为什么是一长串 hex？`4ACFE320...` 和 `6BB4837E...` 哪个对？

**结论**：`canal.admin.passwd = 6BB4837EB74329105EE4568DDA7DC67ED2CA2AD9` 正确。

它是对明文密码 `123456` 做**双重 SHA-1** 的结果：

```text
SHA1(SHA1("123456")) = 6BB4837EB74329105EE4568DDA7DC67ED2CA2AD9
SHA1(SHA1("admin"))  = 4ACFE3202A5FF5CF467898FC58AAB1D615029441
```

即：`4ACFE320...` 格式对，但对应明文是 `admin` 而不是 `123456`，所以换上仍报 `auth :admin is failed`；MD5 格式（32 位 hex）也不对。

**为什么是双重 SHA-1（原理）**：

反编译 Admin 端 `PollingConfigController` 得到校验逻辑：

- Admin 进程启动时生成随机种子 `seeds = RandomStringUtils.random(16)`（每次重启都不同）；
- 校验时计算 `scramble411(adminPasswd, seeds)`，再与请求密码做 `scrambleServerAuth` 比对；
- Server 端把 `canal.admin.passwd` 原样放进请求。

关键数学事实：如果 Server 发送的是**密码的双重 SHA-1**（`X = SHA1(SHA1(pw))`），那么：

```text
正确值 correct = SHA1(pw) XOR SHA1(seeds + X)
校验时 result  = correct XOR SHA1(seeds + X) = SHA1(pw)
最终比对 SHA1(result) == X 成立（与随机种子无关）
```

所以**任何随机种子下，双重 SHA-1 都能通过校验**，这也是官方文档给的值"不像普通密码"的原因。

**踩坑记录**：明文 `123456`、MD5、以及 `admin` 的双重 SHA-1 都会报 `auth :admin is failed`；只有与 Admin `adminPasswd` 对应的双重 SHA-1 才对。

---

## 三、Canal Server 接入 Admin（manager 模式）

### Q4. Canal Server 如何被 Admin 管理？

**修改 deployer 的 `conf/canal.properties`**：

```properties
canal.admin.manager = http://127.0.0.1:8089/
canal.admin.port = 11110
canal.admin.user = admin
canal.admin.passwd = 6BB4837EB74329105EE4568DDA7DC67ED2CA2AD9
canal.admin.register.auto = true
```

启动后：

- 节点自动注册到 `canal_manager.canal_node_server`（ip/admin_port/tcp_port/metric_port）；
- **manager 模式**下，实例配置不再读本地 `conf/example/instance.properties`，而是从 `canal_instance_config` 表拉取；
- Admin 的"配置管理"里的服务端配置对应 `canal_config` 表，建议与本地 `canal.properties` 保持一致，避免 UI 展示与真实配置不一致。

**实例配置（canal_instance_config）关键点**：

- `server_id` 指向节点，`status='1'` 的实例才会被节点拉取启动（服务端 `getInstancesConfig` 只返回 `status=1` 的行）；
- 内容就是一份 properties 文本（源库地址、账号、过滤正则、MQ topic 等），`content_md5` 由 Admin 自动计算；
- 节点每隔几秒轮询，配置变化会触发实例重启，**改配置不需要重启 Server**。

**一键脚本**：`scripts/start-canal.sh`。

> 坑：Canal 1.1.8 的 RocketMQ/Kafka 连接器在 `plugin/` 目录，而官方 `bin/startup.sh` 只加载 `lib/*`，直接跑会报 `Extension instance(name: rocketmq ...) could not be instantiated`。脚本已改为自行拼 classpath：`conf + lib/* + plugin/*`。

### Q5. Server 管理与 Instance 管理有什么区别？

**Server 管理**：管"进程/节点"——节点是否在线、服务端全局配置（`serverMode`、内存、MQ 地址、admin 注册）、启停整个 Server（连带所有实例）、Server 整体日志与指标。

**Instance 管理**：管"具体同步任务"——源库地址、账号、过滤正则、MQ topic、单个实例启停、实例日志与位点。

| 对比 | Server 管理 | Instance 管理 |
|---|---|---|
| 管理对象 | 一个 Canal 进程（节点） | 进程内的一条同步任务 |
| 配置 | canal.properties（全局） | 实例属性（源库/过滤/MQ） |
| 启停影响 | 重启 Server = 重启所有实例 | 只影响该实例 |
| 对应目录 | canal.deployer-*/ | logs/example/ 等实例日志与 meta |

**比喻**：Server 像 Tomcat，Instance 像部署在 Tomcat 里的 Web 应用。改 Tomcat 配置要重启 Tomcat；改某个应用配置只重启那个应用。

---

## 四、消息格式与 binlog 位点

### Q6. 为什么日志提示"消息缺少 binlog 位点，跳过幂等去重"？

**根因**：`canal.mq.flatMessage=true` 时，投递的是 **JSON 平铺消息**，而 FlatMessage 模型（反编译 canal 1.1.8 的 `FlatMessage` 类确认）字段只有：

```text
id / database / table / pkNames / isDdl / type / es / ts / sql
/ sqlType / mysqlType / data / old / gtid
```

**没有 `logFileName` / `logFileOffset`**。binlog 位点只存在于原始 Entry（protobuf）格式的 Header 里，JSON 平铺格式在转换时被丢弃。

因此消费端 `CanalMessage.logFileName` 永远为 null，幂等门面走"缺少位点 → warn 后直接执行"的兜底分支。

**影响**：对天然幂等的 Handler 无影响；对非幂等 Handler（累加、发通知），MQ 重试/重复投递时可能重复执行，因为去重键拿不到。

### Q7. 方案一：flatMessage=false，消费端解析 Entry（推荐做法）

**Canal 配置**（本地文件 + Admin 数据库同步）：

```properties
canal.mq.flatMessage = false
```

**非 flat 消息体格式**（反编译 `CanalMessageSerializerUtil` 确认）：

```text
CanalPacket.Packet (type=MESSAGES, version=1)
  └─ body: CanalPacket.Messages (batchId + messages[])
        └─ 每条 message: CanalEntry.Entry (Header 含 logfileName/logfileOffset)
```

**消费端改造**：

- `CanalSyncConsumer` 从 `RocketMQListener<String>` 改为 `RocketMQListener<MessageExt>`；
- `CanalPacketParser`：`Packet.parseFrom(body)` → `Messages` → `Entry` 列表；
- `CanalEventConverter`：按行拆分事件，`data/old` 语义与 flatMessage 对齐：
  - INSERT：`data=新值, old=null`
  - UPDATE：`data=新值, old=变更前（仅变更列）`
  - DELETE：`data=被删行, old=null`
- 位点取自 `Entry.Header.logfileName/logfileOffset`。

**效果**（本机实测日志）：

```text
收到表变更 seata_user.users type=UPDATE rows=1 pkNames=[id] pos=mysql-bin.000001:60583
```

### Q8. RocketMQ 客户端版本要不要对齐 broker 5.5.0？

**结论**：维持 `rocketmq-client 5.3.1`（starter 2.3.6 官方配套版本）。

- RocketMQ 客户端与服务端跨版本兼容：实测 5.3.1 客户端 + 5.5.0 broker 连接正常；
- 之前"连不上 nameserver"的根因是 **fastjson2 2.0.31**（见故障排查 Q18），与客户端版本无关；
- Maven Central 上 `rocketmq-acl` 最高只发布到 5.3.2（没有 5.5.0），想"全家桶对齐 5.5.0"目前做不到，会形成混合版本；
- 只有需要 5.5.0 新协议/特性（v2 remoting 等）时才值得升 client，届时等官方 starter 配套版本。

---

## 五、消费端架构与路由分发

### Q9. CanalSyncConsumer 的业务逻辑怎么组织？

**路由分发骨架**：

```text
MQ 消息 → 解析（JSON 或 Entry）→ 按 database.table 路由 → 分发到对应 TableSyncHandler
```

核心设计：

- `TableSyncHandler` 接口：一个表一个实现，`supportedKey()` 返回 `"database.table"`；
- 路由表由 Spring 收集所有 Handler Bean 自动组装，**新增表监听只需加一个 Handler**，消费端零改动；
- 异常策略：解析失败 → 记 error 跳过（畸形消息重试无意义）；Handler 业务异常 → 向上抛出交给 RocketMQ 重试。

示例：

```java
@Component
public class UserHandler implements TableSyncHandler {
    static final String KEY = "seata_user.users";
    @Override public String supportedKey() { return KEY; }
    @Override public void handle(CanalMessage message) {
        log.info("收到表变更 {} type={} rows={} pkNames={} pos={}:{}",
                message.routeKey(), message.type(),
                message.data() == null ? 0 : message.data().size(),
                message.pkNames(), message.logFileName(), message.logFileOffset());
    }
}
```

### Q10. canal.instance.filter.regex 配库还是配表？新增表要重启吗？

**建议配到具体的表**（如 `seata_user\\.users`），好处：

- 减少无关 binlog 事件进入 MQ，降低消费端无效消息量；
- 明确订阅边界，避免误监听（如 `.*\\..*` 会把 flyway 历史表、临时表全带进来）。

**新增表要重启吗**：

- **manager 模式（Admin 管理）**：在 Admin 里改实例配置 → 节点轮询发现配置变化 → 自动重启该实例，**不需要重启 Server**；
- 本地文件模式：改 `conf/example/instance.properties` 后需重启 Server（或依赖 auto.scan）。

**生产建议**：过滤规则尽量收敛到业务表；若表很多且频繁新增，可以配置宽泛正则 + 消费端按表路由（未注册的表直接跳过），两者权衡。

---

## 六、幂等与去重

### Q11. Handler 不幂等怎么办？（幂等门面 + DB 去重表）

**背景**：Canal + MQ 是 **at-least-once** 语义，消费端可能收到重复消息（MQ 重试、重复投递、消费后提交前宕机）。

**方案**：幂等门面 + `sync_log` 去重表，去重记录与业务在**同一本地事务**：

```java
public void executeWithDedup(CanalMessage message, String rowKey, Runnable business) {
    if (message.logFileName() == null || message.logFileName().isBlank()) {
        log.warn("消息缺少 binlog 位点，跳过幂等去重: {}", message.routeKey());
        business.run();          // 兜底：依赖 Handler 自身幂等
        return;
    }
    transactionTemplate.executeWithoutResult(status -> {
        int claimed = syncLogMapper.insertIgnore(SyncLog.builder()
                .logFileName(message.logFileName())
                .logFileOffset(message.logFileOffset())
                .rowKey(rowKey)
                .build());
        if (claimed == 0) {      // 重复位点：已处理过
            log.info("重复消息跳过（sync_log 唯一索引）: ...");
            return;
        }
        business.run();          // 业务失败 → 异常上抛 → 事务回滚（含去重记录）→ MQ 重试
    });
}
```

要点：

- `INSERT IGNORE` 返回 1 = 抢占成功，0 = 重复；
- 业务抛异常 → 事务整体回滚（去重记录一起回滚）→ MQ 重试可重新执行；
- 天然幂等的 Handler（删缓存/upsert/日志）不走门面，零额外开销（`TableSyncHandler.idempotent()` 默认 true）。

### Q12. 同一条多行 SQL 的事件共享位点，去重键怎么设计？

**坑**：反编译 `LogEventConvert` 确认，Canal 生成多行事件的 Entry 时，`logfileOffset` 取的是**事件起始位点（logPos - eventLen）**，**同一事件的多个行共享同一个位点**。如果去重键只用 `(log_file_name, log_file_offset)`，多行 UPDATE 的第二行会被误判为重复而跳过！

**方案**：增加行级 `row_key`：

- 有主键：主键值拼接（如 `3`、`1,2`）；
- 无主键表：退化为消息内行号（`r0`、`r1`...）。

唯一键升级为 `(log_file_name, log_file_offset, row_key)`（V4 迁移）：

```sql
ALTER TABLE sync_log
    ADD COLUMN row_key VARCHAR(128) NOT NULL DEFAULT '' AFTER log_file_offset,
    DROP INDEX uk_sync_log_position,
    ADD UNIQUE KEY uk_sync_log_position (log_file_name, log_file_offset, row_key);
```

**实测验证**（一次 `UPDATE users SET ... WHERE id IN (1,2)`）：

```text
sync_log:
mysql-bin.000001  59462  2
mysql-bin.000001  59462  1     ← 同一位点、不同 row_key，两条都正常处理
```

### Q13. 位点 + 行级 key 的完整链路

```text
Entry.Header(logfileName, logfileOffset)
  + 行级 key（主键值 / 行号）
  → sync_log 唯一索引 (file, offset, row_key)
  → 同一消息重投 = 同一 key = 跳过
  → 多行事件 = 不同 row_key = 都处理
```

---

## 七、字段级变更过滤

### Q14. 只关心 users.points 字段，其他字段变更也收到消息怎么办？

**结论**：不要"收到就忽略"，用 UPDATE 消息里的 `old` 判断——**Canal 的 old 只包含真正变更的列**，这正是为字段级判断设计的语义。

判断规则（以 points 为例）：

| 事件 | 规则 | 结果 |
|---|---|---|
| INSERT | 新行，points 有初始值 | 通常需要处理（首次写入） |
| UPDATE | `old` 包含 `points` | points 变了，处理 |
| UPDATE | `old` 不含 `points` | points 没变，跳过 |
| DELETE | 不涉及字段变更 | 按业务忽略 |

**注意**：Canal 端 `canal.instance.filter.field`（如 `users:id/points`）只能**裁剪投递的字段**（消息更小），**不能**按字段变更过滤事件——消息照样会投递，替代不了 `old` 判断。

### Q15. 字段过滤怎么落地？（FieldChangeFilter）

消费端在**进幂等门面之前**过滤（被过滤的事件不写 sync_log、不触发任何业务）：

```java
@Component
public class FieldChangeFilter {
    public boolean fieldChanged(CanalMessage message, String field) {
        if (message.isDdl()) return false;
        return switch (message.type()) {
            case "INSERT" -> true;                              // 新行需要处理
            case "UPDATE" -> oldHasField(message, field);       // old 含该字段 = 变了
            case "DELETE" -> false;
            default -> false;
        };
    }
}
```

`TableSyncHandler` 增加声明式钩子，默认全部处理：

```java
default boolean shouldHandle(CanalMessage message) { return true; }
```

`UserHandler` 只关心 points：

```java
@Override
public boolean shouldHandle(CanalMessage message) {
    return fieldChangeFilter.fieldChanged(message, "points");
}
```

**实测日志**：

```text
points 变更:   old=[{points=11}]      → 收到表变更（处理，写 sync_log）
nickname 变更: old=[{nickname=alice2}] → 字段未变更，跳过（不写 sync_log）
```

### Q16. 踩坑：为什么 old 一直是 null？（updated 标记在 after 列）

**现象**：转换器按 before 列的 `updated` 标记构建 old，实测恒为 null，导致所有 UPDATE 都被字段过滤器跳过。

**根因**（反编译 Canal 官方 `MessageUtil.convert` 确认）：**Canal 把 `updated=true` 标记在 AFTER 列上**。flatMessage 的 old 是这么生成的：

```text
遍历 after 列 → 收集 updated=true 的列名（变更集合）
遍历 before 列 → 按列名过滤，命中变更集合的才放进 old
```

**修正**：按列名匹配 after 列取 `updated`，并叠加 before/after 值比较兜底：

```java
private Map<String, Object> toChangedMap(List<Column> before, List<Column> after) {
    Map<String, Column> afterByName = ...;      // name -> after column
    for (Column b : before) {
        Column a = afterByName.get(b.getName());
        boolean changed = a != null && (a.getUpdated()
                || a.getIsNull() != b.getIsNull()
                || !Objects.equals(a.getValue(), b.getValue()));
        if (changed) map.put(b.getName(), b.getIsNull() ? null : b.getValue());
    }
    return map.isEmpty() ? null : map;
}
```

---

## 八、乱序问题

### Q17. 要防乱序吗？真实落地会有这个问题吗？

**会**。乱序来源：

- 多个消费实例并发拉取（同一 group 内消息按 queue 分给不同消费者）；
- 同一条消息处理失败 → MQ 重试（可能落后于后面的消息）；
- 网络/GC 等导致的消费时间抖动。

**判断影响**：多数"数据同步"场景对最终一致性容忍乱序（缓存最终刷新即可）；但**对状态有强先后依赖的业务（如订单状态机 待支付→已支付→已发货）不能乱**。

**防乱序方案**：

1. **RocketMQ 顺序消息**：按业务主键 hash 到同一 queue（`MessageQueueSelector`），单 queue 内严格 FIFO；注意顺序消费时吞吐受限、且同一分区内一个消息失败会阻塞后续消息；
2. **消费端位点/版本校验**：binlog 位点单调递增，丢弃小于等于已处理位点的事件；
3. **业务字段版本号**：表中加 `version`，更新前校验，旧版本丢弃。

**本项目当前**：消费以日志/缓存刷新为主，对乱序不敏感，未强制顺序；若后续做订单状态流转类消费，建议按主键走顺序消息。

---

## 九、监听粒度与配置管理

### Q18. filter.regex / black.regex 怎么配？

实例配置示例（当前项目）：

```properties
canal.instance.filter.regex=seata_order\\..*,seata_stock\\..*,seata_user\\..*
canal.instance.filter.black.regex=mysql\\.slave_.*
```

- 正则匹配 `库名\\.表名`，注意 properties 里反斜杠要转义（`\\`）；
- 白名单（regex）控制投递哪些表，黑名单（black.regex）排除哪些表；
- 建议白名单收敛到业务表，避免把 flyway 历史表、binlog 元数据表都带进来。

### Q19. 消费端怎么配 topic / 分区？

```properties
canal.mq.topic=canal-topic
canal.mq.partition=0
```

- 单 topic 按表路由：所有表变更进同一个 topic，消费端按 `database.table` 分发（本项目做法，简单）；
- 需要隔离或提高并行度：`canal.mq.dynamicTopic`（按正则路由到不同 topic）或 `canal.mq.partitionsNum` + `partitionHash`（按主键/字段 hash 分区）。

---

## 十、故障排查与踩坑记录

### Q20. 启动报 `auth :admin is failed`

原因与修复见 Q3：`canal.admin.passwd` 必须是 Admin 明文密码的**双重 SHA-1**，且与 Admin `canal.adminPasswd` 一致。

### Q21. 启动报 `Extension instance(name: rocketmq) could not be instantiated`

- 原因：官方 `bin/startup.sh` 只把 `lib/*` 加入 classpath，连接器在 `plugin/` 目录；
- 修复：`start-canal.sh` 自行启动，classpath = `conf + lib/* + plugin/*`。

### Q22. 连不上 RocketMQ nameserver（`RemotingSendRequestException`）

- 现象：TCP 能连，但连接建立后立即被服务端关闭（namesrv 日志：channelActive → channelInactive）；
- 根因：依赖树里 **fastjson2 2.0.31** 抢占了 RocketMQ 客户端需要的 `com.alibaba.fastjson2` 类（`fastjson-2.0.58.jar` 只是 v1 兼容包、不含 fastjson2 类），导致 RemotingCommand 序列化异常、请求发不出去；
- 修复：父 POM `dependencyManagement` 钉 `fastjson2 2.0.59`；
- 排查手段：用 fat jar 精确 classpath 复现 + 二分法定位 + client/namesrv 日志对照。

### Q23. 机器 IP 变了，消息链路全断（Host is down）

- 现象：canal 报 `connect to 192.168.0.106:10911 failed / Host is down`，broker 还广播旧 IP；
- 排查：`ifconfig` 看到新 IP（如 `.107`），`mqadmin clusterList` 看到 broker 注册的还是旧 IP（`.106`）；
- 原因：RocketMQ broker 的 `brokerIP` 在启动时探测并固定，机器换网/DHCP 变更后仍广播旧地址；
- 修复：重启 broker（自动探测新 IP 并重新注册）。生产环境应显式配置 `brokerIP1`。

### Q24. 其他常见问题

| 问题 | 原因/处理 |
|---|---|
| 端口被占用（8089/11110/8091/10911 等） | 先 `lsof -nP -iTCP:<port> -sTCP:LISTEN` 确认，再停旧进程；脚本均带"已运行则重启"逻辑 |
| Admin API 报 `Expired token` | token 有过期时间，重新 `POST /api/v1/user/login` 拿新 token |
| 实例不在节点上启动 | 检查 `canal_instance_config.status` 必须为 `'1'`；服务端只拉取 status=1 的实例 |
| 消息还是 JSON（改造后） | 确认实例/服务端配置 `canal.mq.flatMessage=false` 且 Server 已重启 |
| 消费端频繁"解析 Canal 消息失败" | 消费端代码与 flatMessage 配置不匹配（新旧格式混用），需一起切换 |

---

## 十一、面试要点速记

**选型**：

- 业务事件（订单支付、注册）→ 业务代码发 MQ（事务消息保证一致性）；
- 数据同步（缓存、ES、数仓、备份）→ Canal + MQ；
- 判断标准：事件是"业务动作"还是"数据变化"；写库入口是否单一可控。

**架构链路**：

```text
MySQL binlog → Canal Server(deployer) → RocketMQ → 消费端(CanalSyncConsumer)
                                              ↑
                        Canal Admin(canal_manager 库) 管理节点与实例配置
```

**必背知识点**：

1. flatMessage JSON 不带 binlog 位点；位点只在 Entry protobuf Header；
2. Canal 把 `updated` 标记在 after 列，old = before 列按变更集合过滤；
3. 同一条多行 SQL 的多行 Entry 共享位点，去重键要加行级 key；
4. 幂等门面：sync_log 唯一索引 + 与业务同事务，INSERT IGNORE 抢占；
5. `canal.admin.passwd` 是明文密码的双重 SHA-1（与随机种子无关的认证技巧）；
6. manager 模式实例配置存数据库，改配置自动重启实例，不用重启 Server；
7. 乱序处理：顺序消息按业务主键分区 / 位点单调校验 / 版本号。

**一句话总结**：Canal 是"以数据库为准的数据变更管道"，用它做缓存刷新、搜索同步、数仓、审计这类"数据流"；业务"事件流"仍走业务 MQ。二者组合才是生产级做法。
