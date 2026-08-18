# task-service 任务调度模块设计方案

> 状态：**已评审通过**（2026-08-18）——评审补充：三个任务均采用 **XXL-JOB 分片广播模式（SHARDING_BROADCAST）** 分片读取

## 1. 总体设计

### 1.1 技术选型

| 关注点 | 方案 |
|---|---|
| 框架 | Spring Boot 3.5 / JDK 21 / Maven |
| ORM | MyBatis-Plus 3.5.x + 手写 XML SQL（与项目风格一致） |
| 多数据源 | Baomidou `dynamic-datasource-spring-boot3-starter`（`@DS` 注解路由） |
| Redis | `spring-boot-starter-data-redis`（StringRedisTemplate + pipeline 批量写） |
| 任务调度 | `xxl-job-core 3.4.2`（Executor 模式，注册到本地 Admin `http://127.0.0.1:7080/`） |

### 1.2 架构

```text
XXL-JOB Admin (:7080，cron 触发)
        │ 注册/调度
        ▼
task-service（Executor，端口 9999）
  ├─ RefreshOrderTask   → @DS("order")   → orders_0~3 分批 → Redis(task:order:*，TTL 3d)
  ├─ RefreshUserTask    → @DS("user")    → users 全量分批 → Redis(task:user:*，TTL 7d)
  └─ RefreshProduct     → @DS("stock")   → product 分批  → Redis(task:product:*，TTL 3d)
```

## 2. 模块与工程结构

### 2.1 模块

根 `pom.xml` `<modules>` 增加 `task-service`。

### 2.2 依赖（task-service/pom.xml）

```xml
spring-boot-starter-web
com.baomidou:dynamic-datasource-spring-boot3-starter
com.baomidou:mybatis-plus-spring-boot3-starter
com.mysql:mysql-connector-j（runtime）
org.springframework.boot:spring-boot-starter-data-redis
com.xuxueli:xxl-job-core:3.4.2
lombok（provided）
spring-boot-starter-actuator
spring-boot-starter-test / h2（test）
```

> 版本：dynamic-datasource 与 mybatis-plus 3.5.17 配套（构建时以 dependency:tree 校验，避免 mybatis 版本冲突，参考 user-service 的处理）。

### 2.3 包结构

```text
com.example.task
  TaskApplication
  config/
    XxlJobConfig.java            # Executor Bean（admin/appname/port/logPath）
    TaskProperties.java          # 任务参数（时间范围/批大小/TTL/分片表清单等）
  mapper/
    OrderShardMapper.java        # @DS("order")，XML：orders_0~3 游标分页
    UserMapper.java              # @DS("user")，XML：users 游标分页
    ProductMapper.java           # @DS("stock")，XML：product 游标分页
  task/
    RefreshOrderTask.java        # @XxlJob("refreshOrderTask")
    RefreshUserTask.java         # @XxlJob("refreshUserTask")
    RefreshProduct.java          # @XxlJob("refreshProduct")
  service/
    OrderCacheService.java       # 分批读取 + pipeline 写 Redis（每分片一个游标）
    UserCacheService.java
    ProductCacheService.java
```

## 3. 多数据源设计

### 3.1 配置（application.yml）

```yaml
spring:
  datasource:
    dynamic:
      primary: stock        # 默认数据源
      strict: true          # @DS 未命中直接报错，防止误连
      datasource:
        user:
          url: jdbc:mysql://127.0.0.1:3306/seata_user?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai
          username: root
          password: ""
          driver-class-name: com.mysql.cj.jdbc.Driver
        order:
          url: jdbc:mysql://127.0.0.1:3306/seata_order?...
          username: root
          password: ""
        stock:
          url: jdbc:mysql://127.0.0.1:3306/seata_stock?...
          username: root
          password: ""
```

### 3.2 路由

- Mapper 接口加 `@DS("order")` / `@DS("user")` / `@DS("stock")`，读写均走对应库；
- 本项目不使用 ShardingSphere（task-service 直接访问物理表），避免与 dynamic-datasource 叠加复杂度；
- 三个数据源独立连接池，参数（最大连接、超时）可配置。

## 4. 分批读取与 Redis 写入

### 4.1 游标分页（通用模式）

```sql
-- 时间范围任务（订单/商品）
SELECT id, ...
FROM {table}
WHERE create_time >= #{cutoff} AND id > #{lastId}
ORDER BY id ASC
LIMIT #{batchSize}

-- 全量任务（用户）
SELECT id, nickname, points, create_time   -- 不查敏感列（id_card 等）
FROM users
WHERE id > #{lastId}
ORDER BY id ASC
LIMIT #{batchSize}
```

循环推进 `lastId`，直到某批返回条数 < batchSize 即结束。

### 4.2 分片广播（SHARDING_BROADCAST）

三个任务在 Admin 上均配置为**分片广播**：调度触发时，该执行器的**每个在线实例**都会执行一次，并通过 `XxlJobHelper.getShardIndex() / getShardTotal()` 拿到自己的分片号与总分片数，各实例只处理分配给自己的那部分数据，实现水平并行。

**各任务的分片映射：**

| 任务 | 分片方式 |
|---|---|
| RefreshOrderTask | 物理分片表 `orders_0~3`：`tableIndex % shardTotal == shardIndex` 的实例处理对应表。单实例（shardTotal=1）时处理全部 4 张表；4 实例时各处理 1 张 |
| RefreshUserTask | 全量用户按 `MOD(id, shardTotal) = shardIndex` 分片 + 游标分页 |
| RefreshProduct | 最近 3 天商品按 `MOD(id, shardTotal) = shardIndex` 分片 + 游标分页 |

单实例部署时 shardTotal=1，全部数据由本实例处理（等价于不分片），逻辑无需特判；多实例部署（同 appname、不同 executor 端口）即可自动横向扩展。

### 4.3 订单分表读取（orders_0 ~ orders_3）

- 命中本实例的物理表（`tableIndex % shardTotal == shardIndex`）逐表游标分页：`WHERE create_time >= #{cutoff} AND id > #{lastId} ORDER BY id LIMIT #{batchSize}`；
- 各表结果直接写 Redis（Key 按订单 id，全局唯一，无跨片冲突）；
- 批次间可选 `sleep`（默认 0）。

### 4.4 Redis 数据结构

| 任务 | Key | Value（JSON） | TTL |
|---|---|---|---|
| 订单 | `task:order:{id}` | `{id, userId, productId, count, createTime}` | 3 天 |
| 用户 | `task:user:{id}` | `{id, nickname, points, createTime}`（**不含敏感字段**） | 7 天 |
| 商品 | `task:product:{id}` | `{id, name, brand, price, description, createTime}` | 3 天 |

- 序列化：Jackson，写入用 `StringRedisTemplate`；
- 批量：每批 500 条用 `executePipelined` 一次发出（TTL 用 `expire` 一并设置），降低 RTT；
- 覆盖写（幂等）：相同 id 重复刷新直接覆盖。

## 5. XXL-JOB 集成

### 5.1 Executor 配置（XxlJobConfig）

```java
@Bean
public XxlJobSpringExecutor xxlJobExecutor(TaskProperties props) {
    XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses("http://127.0.0.1:7080/");
    executor.setAppname("task-service");
    executor.setPort(9999);
    executor.setLogPath("/Users/shawn/logs/xxl-job/task-service");
    return executor;
}
```

### 5.2 任务实现

```java
@Component
public class RefreshOrderTask {
    @XxlJob("refreshOrderTask")
    public void refreshOrderTask() { ... }   // 每天 1 次（cron 在 Admin 配置）
}
```

- 每个 handler：记录开始/结束/批数/总条数/耗时；异常向上抛 → Admin 标记失败并保留执行日志；
- Admin 侧配置：执行器 appname=`task-service`，3 个任务 cron 在控制台配置（order/user 每天、product 每小时）。

## 6. 配置（application.yml 任务参数）

```yaml
app:
  task:
    batch-size: 500            # 每批条数
    batch-sleep-ms: 0          # 批间休眠（可选，防突发压力）
    order-days: 3              # 订单/商品时间范围
    product-days: 3
    order-ttl: 3d
    user-ttl: 7d
    product-ttl: 3d
    order-shard-tables: orders_0,orders_1,orders_2,orders_3
xxl:
  job:
    admin-addresses: http://127.0.0.1:7080/
    appname: task-service
    port: 9999
    log-path: /Users/shawn/logs/xxl-job/task-service
```

## 7. 测试方案

1. **Mapper 单测（H2 + 手写 schema）**：
   - `OrderShardMapperTest`：orders_0~3 建表，验证游标分页 SQL（首屏/推进/终止条件）与时间范围过滤；
   - `UserMapperTest` / `ProductMapperTest`：游标分页正确性；
2. **Service 单测（mock Mapper + RedisTemplate）**：
   - 分批循环在末批（< batchSize）正确终止；
   - 每批调用 pipeline 写入且设置 TTL；
   - 用户任务不查询/不写入敏感字段；
3. **任务 handler 单测（mock Service）**：异常向上抛、统计日志输出；
4. **集成验证（本地）**：
   - 启动 task-service → Admin「执行器管理」可见 task-service 在线；
   - Admin 手动触发 3 个任务 → Redis 对应 Key 存在、TTL 正确；
   - 抽样核对 Redis 条数与 DB（orders_0~3 合并 / users / product）一致；
   - 日志确认每批 500 条、批数与总条数输出。

## 8. 涉及文件清单

| 文件 | 说明 |
|---|---|
| 根 `pom.xml` | 增加 `<module>task-service</module>` |
| `task-service/pom.xml` | 依赖（dynamic-datasource / xxl-job-core / redis / mybatis-plus） |
| `task-service/.../TaskApplication.java` | 启动类（scanBasePackages="com.example"） |
| `task-service/.../config/XxlJobConfig.java` | Executor Bean |
| `task-service/.../config/TaskProperties.java` | 任务参数配置 |
| `task-service/.../mapper/{OrderShard,User,Product}Mapper.java` + XML | 游标分页 SQL（@DS 路由） |
| `task-service/.../service/{OrderCache,UserCache,ProductCache}Service.java` | 分批读取 + pipeline 写 Redis |
| `task-service/.../task/{RefreshOrderTask,RefreshUserTask,RefreshProduct}.java` | @XxlJob 任务 |
| `task-service/src/main/resources/application.yml` | 多数据源 / Redis / xxl-job / 任务参数 |
| `task-service/src/test/...` | Mapper / Service / Task 单测 |

## 9. 风险与说明

1. **dynamic-datasource 与 mybatis-plus 版本配套**：构建时核对依赖树，避免 mybatis 双版本冲突（参考 canal 项目踩坑）；
2. **xxl-job-core 3.4.2 与 Spring Boot 3.5 兼容性**：Executor 为独立 netty/http 服务，与 Spring 无强耦合；若出现启动兼容问题（如日志/配置冲突），按错误调整依赖或配置；
3. **覆盖写幂等**：任务重复触发不会产生脏数据，仅重复写入；
4. **大表分批**：游标分页避免深翻页；批间 sleep 可配置，防止对 DB 突刺；
5. **敏感数据**：users 查询列白名单化，身份证等不进入 Redis。

> 设计评审通过后进入编码阶段。
