# sca-practice

Spring Cloud Alibaba 分布式微服务学习实践项目。从零搭建一套多模块微服务应用，覆盖注册发现、配置中心、远程调用、网关、限流降级、分布式事务、消息队列、Dubbo RPC 等核心能力。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 构建 | Java 21 · Maven |
| 微服务框架 | Spring Boot 3.5.16 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 |
| 注册 / 配置中心 | Nacos 3.0.3 |
| 远程调用 | OpenFeign + LoadBalancer · Dubbo 3.3.6 |
| 网关 | Spring Cloud Gateway（路由存 MySQL，动态刷新） |
| 限流降级 | Sentinel Dashboard |
| 分布式事务 | Seata 2.6.0（AT 模式） |
| 消息队列 | RocketMQ 5.5.0（含事务消息） |
| 数据库 | MySQL 8.4 |
| 分库分表 | ShardingSphere 5.5.3（orders → orders_0~3，按 id % 4 分片） |
| 工程化 | Flyway 数据库迁移 · OpenAPI (springdoc) · Actuator 健康检查 · JUnit 5 + Mockito |

## 模块结构

| 模块 | 端口 | 职责 | 涉及组件 |
| --- | --- | --- | --- |
| `sca-common` | — | 共享模块：消息 DTO、业务异常体系、全局异常处理 | Spring Web |
| `dubbo-api` | — | 跨服务共享的 Dubbo 接口定义（`StockDubboService`） | Dubbo |
| `hello-sca` | 8082 | 第一个 Spring Boot 服务，演示 Nacos 配置中心读取 | Nacos Config |
| `user-service` | 8081 | 用户服务：OpenFeign 调用 hello、Sentinel 限流、RocketMQ 消费者 | OpenFeign · Sentinel · RocketMQ |
| `order-service` | 8083 | 订单服务：全局事务发起方（TM）、RocketMQ 事务消息、Dubbo/OpenFeign 调用库存、orders 分表 | Seata · RocketMQ · Dubbo · OpenFeign · ShardingSphere |
| `stock-service` | 8084 | 库存服务：Dubbo 提供者、Seata 分支事务参与方（RM） | Dubbo · Seata |
| `gateway-service` | 8088 | 统一网关：路由从 MySQL `route_config` 表动态加载并定时刷新 | Gateway · MySQL |

## 架构总览

```text
浏览器
  │
  ▼
gateway-service :8088（Spring Cloud Gateway，路由来自 MySQL route_config）
  │
  ├──────────────┬─────────────────────┐
  ▼              ▼                     ▼
user-service   order-service        (hello-sca :8082)
:8081           :8083                     │
  │              │ OpenFeign / Dubbo      │ OpenFeign
  │              ▼                        │
  │          stock-service :8084          │
  │              │                        │
  └──────────────┴────────────────────────┘
        Nacos :8848 注册/配置中心
        Seata Server :8091 全局事务（order + stock 协同）
        RocketMQ :9876 消息/事务消息（order 生产，user 消费）
        Sentinel Dashboard :8858 限流规则
        MySQL 8.4 :3306（seata / seata_order / seata_stock / gateway_dashboard）
```

调用链示例：`下单` 由 order-service 发起 Seata 全局事务，本地写订单 + 通过 Dubbo/Feign 扣库存，任一分支失败两库同时回滚；同时通过 RocketMQ 事务消息发送积分消息，user-service 消费后发放积分。

## 本地环境准备

- JDK 21、Maven 3.9+
- MySQL 8.4（root 本地无密码，见各服务 `application.yml`）
- Nacos 3.0.3（API 端口 8848；本机控制台已调整为 8847）
- Seata Server 2.6.0（8091，db 存储，注册/配置中心走 Nacos）
- RocketMQ 5.5.0（NameServer 9876 / Broker 10911）
- Sentinel Dashboard（8858）
- RocketMQ Dashboard（7070，可选，用于可视化查看集群/消息）

> 本仓库 `scripts/` 目录不含中间件脚本；对应的一键启停脚本（nacos / rocketmq / seata / sentinel / rocketmq-dashboard）在同系列仓库的 `scripts/` 中。

## 快速开始

### 1. 初始化数据库

```bash
# Seata Server 存储库（TC 状态表：global_table / branch_table / lock_table ...）
mysql -u root -e "CREATE DATABASE IF NOT EXISTS seata DEFAULT CHARACTER SET utf8mb4;"
mysql -u root seata < sql/seata-server-mysql.sql

# 业务库：order/stock 服务启动时由 Flyway 自动建表（orders/stock/undo_log + 初始库存），
# 数据库本身也会由 JDBC URL 的 createDatabaseIfNotExist=true 自动创建。
# 如需手动初始化（等价于 Flyway 做的工作），仍可执行：
mysql -u root < sql/init-seata.sql

# gateway-service 依赖的 gateway_dashboard 库（route_config 等表）
# 建表脚本见配套项目 spring-cloud-gateway-dashboard/backend/src/main/resources/db/migration/
```

### 2. 启动中间件

先启动 Nacos，再启动 RocketMQ、Seata Server、Sentinel Dashboard（RocketMQ Dashboard 可选）。全部就绪后：

```bash
# 根目录构建（多模块）
mvn clean package -Dmaven.test.skip=true
```

### 3. 启动服务

建议顺序（后启动的服务依赖先启动的注册中心/中间件）：

```bash
cd user-service   && java -jar target/user-service-0.0.1-SNAPSHOT.jar   # 8081
cd order-service  && java -jar target/order-service-0.0.1-SNAPSHOT.jar  # 8083
cd stock-service  && java -jar target/stock-service-0.0.1-SNAPSHOT.jar  # 8084
cd gateway-service && java -jar target/gateway-service-0.0.1-SNAPSHOT.jar # 8088
```

> 各模块 jar 名以实际构建产物为准；IDE 里直接启动各模块的 `*Application.java` 亦可。

### 4. 验证

- Nacos 控制台"服务列表"能看到 user/order/stock/gateway 等服务实例；
- 直接调各服务接口（如 `GET http://127.0.0.1:8083/order/...`）或通过网关 `http://127.0.0.1:8088/...` 访问；
- 触发"下单 + 扣库存"链路的失败场景，观察两库数据是否同步回滚（Seata AT）；
- 打开 RocketMQ Dashboard `http://127.0.0.1:7070` 查看 topic 与消息轨迹。

## 工程化能力

- **数据库迁移**：order/stock 服务内置 Flyway（`src/main/resources/db/migration/`），启动自动建表、幂等可重复执行；`baseline-on-migrate` 兼容已手动建表的旧库；
- **API 文档**：每个服务内置 springdoc，访问 `http://127.0.0.1:{端口}/swagger-ui.html` 查看/调试接口；
- **健康检查**：每个服务暴露 `http://127.0.0.1:{端口}/actuator/health`（含 `info`、`metrics`）；
- **单元测试**：`mvn test`（核心服务 + 共享模块共 13 个用例，覆盖异常映射、扣库存、下单与消息时序）。

## 实践问答文档

- [ShardingSphere 分表 + Seata 实践问答全记录](doc/shardingsphere-seata-practice-qa.md)：分表设计、Seata × ShardingSphere 官方集成、双数据源本地/分布式事务显式区分、@MapperScan 批量注册（HTML 版在本地课件 `lessons/0026-shardingsphere-seata-practice-qa.html`，不入库）
- [ShardingSphere 加密 + 脱敏性能基准测试](doc/shardingsphere-encrypt-mask-benchmark.md)：AES 解密 + KEEP_FIRST_N_LAST_M 脱敏的微基准与真实链路批量基准（每行额外约 0.5µs，线性增长）
- [Canal 学习与实践问答全记录](doc/canal-practice-qa.md)：Canal 选型、环境搭建、消费端路由分发、幂等与字段级过滤
- [Leaf 实践问答全记录](doc/leaf-practice-qa.md)：Leaf 选型、号段模式正确性、环境搭建、依赖排障

## 目录结构

```text
sca-practice/
├── pom.xml                 # 父 POM：统一版本管理（双 BOM + Dubbo/RocketMQ 版本）
├── sca-common/             # 共享模块：DTO / 业务异常 / 全局异常处理
├── dubbo-api/              # Dubbo 公共接口
├── hello-sca/              # 微服务入门服务
├── user-service/           # 用户服务
├── order-service/          # 订单服务（TM + 事务消息）
├── stock-service/          # 库存服务（RM + Dubbo 提供者）
├── gateway-service/        # 网关（DB 动态路由）
└── sql/                    # Seata / 业务库建表脚本
```

## 说明

- 各服务 `application.yml` 中的账号密码均为**本地开发环境默认值**（如 Nacos `nacos/nacos`、MySQL root 空密码、网关 `gateway123`），仅用于本机学习；部署到真实环境前应通过环境变量 / 配置中心外部化并脱敏。
- 本仓库默认以**私有仓库**管理。
