# task-service 任务调度模块需求文档

> 状态：**已评审通过**（2026-08-18，评审结论见第 6 节）——已进入设计阶段
>
> 相关环境：XXL-JOB Admin 3.4.2 已运行于 `http://127.0.0.1:7080/`（账号 admin/123456，context-path=/）；Redis 6379 已运行。

## 1. 背景与目标

业务数据（订单、用户、商品）散落在 `seata_order` / `seata_user` / `seata_stock` 三个库中，查询侧（如报表、列表页）需要频繁读取。为降低对业务库的实时查询压力、提升读取速度，建设独立的**任务调度服务 task-service**：

- 作为 XXL-JOB 的**执行器（Executor）**接入调度中心，承载定时缓存刷新任务；
- 连接三个业务数据源，**分批**读取数据后写入 Redis；
- 通过「任务 + TTL」的方式，让 Redis 中的数据在有效期内可被业务侧直接读取。

## 2. 范围

**本期包含：**

- 新建 Maven 模块 `task-service`（Spring Boot 独立服务）；
- 多数据源：`seata_user` / `seata_order` / `seata_stock`；
- 集成 XXL-JOB 3.4.2（Executor 模式，注册到本地 Admin）；
- 3 个定时任务：RefreshOrderTask / RefreshUserTask / RefreshProduct；
- 分批（分页）读取 + Redis 批量写入；
- 任务执行日志与失败可观测。

**本期不包含：**

- 业务侧读取 Redis 的接口/消费方改造（本期只做"刷入"，读取由各业务服务后续自行接入）；
- XXL-JOB Admin 本身的改造；
- 数据一致性校验/对账平台。

## 3. 功能需求

### 3.1 模块与多数据源

- 新增 `task-service` 模块（加入根 pom 的 `<modules>`），独立 Spring Boot 应用；
- 连接三个数据源：`seata_user`（用户）、`seata_order`（订单）、`seata_stock`（商品/库存）；
- 三个数据源之间读写隔离，任务按数据源路由，互不影响；
- 数据源连接池参数（大小、超时）可配置。

### 3.2 XXL-JOB 集成（Executor 模式）

- 引入 `xxl-job-core 3.4.2`，以 Executor 方式注册到 Admin（`http://127.0.0.1:7080`）；
- Executor 基本配置（appname、端口、Admin 地址、accessToken）可配置；
- 3 个任务均通过 `@XxlJob` 注解实现，触发规则（cron）在 Admin 控制台配置，代码不做硬编码 cron；
- Admin 上可手动触发一次（便于验收），失败有执行日志可查。

### 3.3 RefreshOrderTask（订单）

| 项 | 要求 |
|---|---|
| 数据范围 | `seata_order.orders_0 ~ orders_3` 分片表（主表 orders 已废弃，按 `id % 4` 路由）中**最近 3 天**（`create_time` >= 当前时间 - 3 天）的订单，4 张分片表合并读取 |
| 触发规则 | 每天执行 1 次（cron 在 Admin 配置，默认建议凌晨低峰，如 `0 30 2 * * ?`） |
| Redis 存储 | 订单数据写入 Redis，**TTL 3 天** |
| 数据量 | 分批读取（见 3.6），避免一次性全量 |

### 3.4 RefreshUserTask（用户）

| 项 | 要求 |
|---|---|
| 数据范围 | `seata_user.users` 表**全部**用户 |
| 触发规则 | 每天执行 1 次（cron 在 Admin 配置） |
| Redis 存储 | 用户数据写入 Redis，**TTL 7 天** |
| 敏感字段 | 刷入 Redis 的用户数据**不得包含明文敏感信息**（如身份证号等），仅包含业务展示所需字段或做脱敏处理 |
| 数据量 | 分批读取（见 3.6） |

### 3.5 RefreshProduct（商品）

| 项 | 要求 |
|---|---|
| 数据范围 | `seata_stock.product` 表**最近 3 天**（`create_time` >= 当前时间 - 3 天）的商品 |
| 触发规则 | **每小时**执行 1 次（cron 在 Admin 配置，如 `0 0 * * * ?`） |
| Redis 存储 | 商品数据写入 Redis，**TTL 3 天**（已确认） |
| 数据量 | 分批读取（见 3.6） |

### 3.6 分批获取（核心要求）

- 三个任务读取 DB 一律**分批（分页）**，禁止一次性 `SELECT *` 全量加载；
- 分批策略：
  - 每批默认 **500 条**（可配置）；
  - 使用**游标分页**（按 `id` 或 `create_time` 排序 + 游标推进），避免 offset 深翻页的性能退化；
  - 订单分表：对 `orders_0 ~ orders_3` **逐片**游标分页（每张分片表独立游标），各片结果合并写入；
  - 每批读取完成后立即写入 Redis（建议 pipeline 批量写），再取下一批；
- 分批大小、时间范围（最近 N 天）、Redis TTL 均可配置化。

### 3.7 Redis 数据约定（概要，细节在设计文档定）

- Key 命名建议统一前缀，如 `task:order:xxx` / `task:user:xxx` / `task:product:xxx`，便于区分与管理；
- 每个实体一个 Key 或按业务约定结构（设计文档确定序列化方式）；
- TTL：订单 3 天、用户 7 天、商品见 3.5；
- 写入时若 Key 已存在：**全量覆盖刷新**（以 DB 为准）。

## 4. 非功能需求

1. **幂等/重复执行安全**：任务失败重试或重复触发时，重复刷入不影响正确性（覆盖写天然幂等；记录执行日志便于排查）；
2. **可配置**：数据源连接、Admin 地址、Executor 端口、分批大小、时间范围、Redis TTL、cron 均可配置；
3. **可观测**：每次任务执行记录开始/结束/条数/耗时/成功失败；失败时输出错误日志（XXL-JOB Admin 可查执行日志）；
4. **对 DB 友好**：分批 + 游标分页，避免大查询；批间可加短暂间隔（可选，防止突发压力）；
5. **对 Redis 友好**：批量写入使用 pipeline，减少 RTT；
6. **启动失败快速暴露**：Executor 注册失败或任一数据源不可用时启动即报错，不静默降级。

## 5. 验收标准

1. `task-service` 独立启动，注册为 XXL-JOB Executor，在 Admin（7080）「执行器管理」可见且在线；
2. Admin 中可配置 3 个任务并**手动触发**成功；
3. 触发后对应 Redis Key 存在：
   - RefreshOrderTask：最近 3 天订单数据，TTL ≈ 3 天；
   - RefreshUserTask：全部用户数据，TTL ≈ 7 天，且无明文敏感字段；
   - RefreshProduct：最近 3 天商品数据，TTL ≈ 3 天；
4. 数据量与 DB 查询结果一致（抽样核对）；
5. 分批读取生效：单批数量符合配置（日志可证），DB 侧无一次性大查询；
6. 任务失败时 Admin 有失败执行日志，服务不崩。

## 6. 评审结论（2026-08-18）

| # | 问题 | 结论 |
|---|---|---|
| 1 | RefreshProduct 的 Redis TTL | **3 天**（与数据范围一致） |
| 2 | 任务执行记录 | **仅日志**，不落执行记录表 |
| 3 | Executor 端口 | **9999** |
| 4 | 订单数据来源 | **读取分表 `orders_0 ~ orders_3`**，主表 `orders` 已废弃（按 `id % 4` 分片） |

---

> 需求评审通过后，进入设计文档阶段（模块骨架、多数据源方案、Redis 数据结构、分批实现、XXL-JOB 注册与任务实现、测试方案）。
